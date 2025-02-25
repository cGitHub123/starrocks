// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

#include "exec/vectorized/union_node.h"

#include "column/column_helper.h"
#include "column/nullable_column.h"
#include "exprs/expr.h"
#include "exprs/expr_context.h"

namespace starrocks::vectorized {
UnionNode::UnionNode(ObjectPool* pool, const TPlanNode& tnode, const DescriptorTbl& descs)
        : ExecNode(pool, tnode, descs),
          _first_materialized_child_idx(tnode.union_node.first_materialized_child_idx),
          _tuple_id(tnode.union_node.tuple_id) {}

Status UnionNode::init(const TPlanNode& tnode, RuntimeState* state) {
    RETURN_IF_ERROR(ExecNode::init(tnode, state));

    const auto& const_expr_lists = tnode.union_node.const_expr_lists;
    for (const auto& exprs : const_expr_lists) {
        std::vector<ExprContext*> ctxs;
        RETURN_IF_ERROR(Expr::create_expr_trees(_pool, exprs, &ctxs));
        _const_expr_lists.push_back(ctxs);
    }

    const auto& result_expr_lists = tnode.union_node.result_expr_lists;
    for (const auto& exprs : result_expr_lists) {
        std::vector<ExprContext*> ctxs;
        RETURN_IF_ERROR(Expr::create_expr_trees(_pool, exprs, &ctxs));
        _child_expr_lists.push_back(ctxs);
    }

    if (tnode.union_node.__isset.pass_through_slot_maps) {
        for (const auto& item : tnode.union_node.pass_through_slot_maps) {
            _convert_pass_through_slot_map(item);
        }
    }

    return Status::OK();
}

void UnionNode::_convert_pass_through_slot_map(const std::map<SlotId, SlotId>& slot_map) {
    std::map<SlotId, size_t> tmp_map;
    for (const auto& [key, value] : slot_map) {
        if (tmp_map.find(value) == tmp_map.end()) {
            tmp_map[value] = 1;
        } else {
            tmp_map[value]++;
        }
    }

    std::map<SlotId, SlotItem> tuple_slot_map;
    for (const auto& [key, value] : slot_map) {
        tuple_slot_map[key] = {value, tmp_map[value]};
    }
    _pass_through_slot_maps.emplace_back(tuple_slot_map);
}

Status UnionNode::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(ExecNode::prepare(state));

    _tuple_desc = state->desc_tbl().get_tuple_descriptor(_tuple_id);

    for (const vector<ExprContext*>& exprs : _const_expr_lists) {
        RETURN_IF_ERROR(Expr::prepare(exprs, state, row_desc(), expr_mem_tracker()));
    }

    for (size_t i = 0; i < _child_expr_lists.size(); i++) {
        RETURN_IF_ERROR(Expr::prepare(_child_expr_lists[i], state, child(i)->row_desc(), expr_mem_tracker()));
    }

    return Status::OK();
}

Status UnionNode::open(RuntimeState* state) {
    SCOPED_TIMER(_runtime_profile->total_time_counter());

    RETURN_IF_ERROR(ExecNode::open(state));

    for (const vector<ExprContext*>& exprs : _const_expr_lists) {
        RETURN_IF_ERROR(Expr::open(exprs, state));
    }

    for (const vector<ExprContext*>& exprs : _child_expr_lists) {
        RETURN_IF_ERROR(Expr::open(exprs, state));
    }

    if (!_children.empty()) {
        RETURN_IF_ERROR(child(_child_idx)->open(state));
    }

    return Status::OK();
}

Status UnionNode::get_next(RuntimeState* state, RowBatch* row_batch, bool* eos) {
    return Status::NotSupported("get_next for row_batch is not supported");
}

Status UnionNode::get_next(RuntimeState* state, ChunkPtr* chunk, bool* eos) {
    SCOPED_TIMER(_runtime_profile->total_time_counter());
    RETURN_IF_CANCELLED(state);

    while (true) {
        //@TODO: There seems to be no difference between passthrough and materialize, can be
        // remove the passthrough handle
        if (_has_more_passthrough()) {
            RETURN_IF_ERROR(_get_next_passthrough(state, chunk));
            if (!_child_eos) {
                *eos = false;
                break;
            }
        } else if (_has_more_materialized()) {
            RETURN_IF_ERROR(_get_next_materialize(state, chunk));
            if (!_child_eos) {
                *eos = false;
                break;
            }
        } else if (_has_more_const(state)) {
            RETURN_IF_ERROR(_get_next_const(state, chunk));
            *eos = false;
            break;
        } else {
            *eos = true;
            break;
        }
    }

    if (*eos) {
        COUNTER_SET(_rows_returned_counter, _num_rows_returned);
    } else {
        _num_rows_returned += (*chunk)->num_rows();
    }

    DCHECK_CHUNK(*chunk);
    return Status::OK();
}

Status UnionNode::close(RuntimeState* state) {
    if (is_closed()) {
        return Status::OK();
    }
    for (auto& exprs : _child_expr_lists) {
        Expr::close(exprs, state);
    }
    for (auto& exprs : _const_expr_lists) {
        Expr::close(exprs, state);
    }
    return ExecNode::close(state);
}

Status UnionNode::_get_next_passthrough(RuntimeState* state, ChunkPtr* chunk) {
    (*chunk) = std::make_shared<Chunk>();
    ChunkPtr tmp_chunk = nullptr;

    if (_child_eos) {
        RETURN_IF_ERROR(child(_child_idx)->open(state));
        _child_eos = false;
    }

    while (true) {
        RETURN_IF_ERROR(child(_child_idx)->get_next(state, &tmp_chunk, &_child_eos));
        if (_child_eos) {
            RETURN_IF_ERROR(child(_child_idx)->close(state));
            _child_idx++;
            break;
        }

        if (tmp_chunk->num_rows() <= 0) {
            continue;
        }

        _move_passthrough_chunk(tmp_chunk, *chunk);
        break;
    }

    return Status::OK();
}

Status UnionNode::_get_next_materialize(RuntimeState* state, ChunkPtr* chunk) {
    (*chunk) = std::make_shared<Chunk>();
    ChunkPtr tmp_chunk = nullptr;
    if (_child_eos) {
        RETURN_IF_ERROR(child(_child_idx)->open(state));
        _child_eos = false;
    }

    while (true) {
        RETURN_IF_ERROR(child(_child_idx)->get_next(state, &tmp_chunk, &_child_eos));
        if (_child_eos) {
            RETURN_IF_ERROR(child(_child_idx)->close(state));
            _child_idx++;
            break;
        }

        if (tmp_chunk->num_rows() <= 0) {
            continue;
        }

        _move_materialize_chunk(tmp_chunk, *chunk);
        break;
    }

    return Status::OK();
}

Status UnionNode::_get_next_const(RuntimeState* state, ChunkPtr* chunk) {
    *chunk = std::make_shared<Chunk>();

    RETURN_IF_ERROR(_move_const_chunk(*chunk));

    _const_expr_list_idx++;
    return Status::OK();
}

void UnionNode::_move_passthrough_chunk(ChunkPtr& src_chunk, ChunkPtr& dest_chunk) {
    const auto& tuple_descs = child(_child_idx)->row_desc().tuple_descriptors();

    if (!_pass_through_slot_maps.empty()) {
        for (auto* dest_slot : _tuple_desc->slots()) {
            auto slot_item = _pass_through_slot_maps[_child_idx][dest_slot->id()];
            ColumnPtr& column = src_chunk->get_column_by_slot_id(slot_item.slot_id);
            // There may be multiple DestSlotId mapped to the same SrcSlotId,
            // so here we have to decide whether you can MoveColumn according to this situation
            if (slot_item.ref_count <= 1) {
                _move_column(dest_chunk, column, dest_slot, src_chunk->num_rows());
            } else {
                _clone_column(dest_chunk, column, dest_slot, src_chunk->num_rows());
            }
        }
    } else {
        // For backward compatibility
        // TODO(kks): when StarRocks 2.0 release, we could remove this branch.
        size_t index = 0;
        // When pass through, the child tuple size must be 1;
        for (auto* src_slot : tuple_descs[0]->slots()) {
            auto* dest_slot = _tuple_desc->slots()[index++];
            ColumnPtr& column = src_chunk->get_column_by_slot_id(src_slot->id());
            _move_column(dest_chunk, column, dest_slot, src_chunk->num_rows());
        }
    }
}

void UnionNode::_move_materialize_chunk(ChunkPtr& src_chunk, ChunkPtr& dest_chunk) {
    for (size_t i = 0; i < _child_expr_lists[_child_idx].size(); i++) {
        auto* dest_slot = _tuple_desc->slots()[i];
        ColumnPtr column = _child_expr_lists[_child_idx][i]->evaluate(src_chunk.get());

        _move_column(dest_chunk, column, dest_slot, src_chunk->num_rows());
    }
}

Status UnionNode::_move_const_chunk(ChunkPtr& dest_chunk) {
    for (size_t i = 0; i < _const_expr_lists[_const_expr_list_idx].size(); i++) {
        ColumnPtr column = _const_expr_lists[_const_expr_list_idx][i]->evaluate(nullptr);
        auto* dest_slot = _tuple_desc->slots()[i];

        _move_column(dest_chunk, column, dest_slot, 1);
    }

    return Status::OK();
}

void UnionNode::_clone_column(ChunkPtr& dest_chunk, const ColumnPtr& src_column, const SlotDescriptor* dest_slot,
                              size_t row_count) {
    if (src_column->is_nullable() || !dest_slot->is_nullable()) {
        dest_chunk->append_column(src_column->clone_shared(), dest_slot->id());
    } else {
        ColumnPtr nullable_column =
                NullableColumn::create(src_column->clone_shared(), NullColumn::create(row_count, 0));
        dest_chunk->append_column(nullable_column, dest_slot->id());
    }
}

void UnionNode::_move_column(ChunkPtr& dest_chunk, ColumnPtr& src_column, const SlotDescriptor* dest_slot,
                             size_t row_count) {
    if (src_column->is_nullable()) {
        if (src_column->is_constant()) {
            auto nullable_column = ColumnHelper::create_column(dest_slot->type(), true);
            nullable_column->reserve(row_count);
            nullable_column->append_nulls(row_count);
            dest_chunk->append_column(std::move(nullable_column), dest_slot->id());
        } else {
            dest_chunk->append_column(src_column, dest_slot->id());
        }
    } else {
        if (src_column->is_constant()) {
            auto* const_column = ColumnHelper::as_raw_column<ConstColumn>(src_column);
            // Note: we must create a new column every time here,
            // because VectorizedLiteral always return a same shared_ptr and we will modify it later.
            ColumnPtr new_column = ColumnHelper::create_column(dest_slot->type(), dest_slot->is_nullable());
            new_column->append(*const_column->data_column(), 0, 1);
            new_column->assign(row_count, 0);
            dest_chunk->append_column(std::move(new_column), dest_slot->id());
        } else {
            if (dest_slot->is_nullable()) {
                ColumnPtr nullable_column = NullableColumn::create(src_column, NullColumn::create(row_count, 0));
                dest_chunk->append_column(std::move(nullable_column), dest_slot->id());
            } else {
                dest_chunk->append_column(src_column, dest_slot->id());
            }
        }
    }
}

} // namespace starrocks::vectorized
