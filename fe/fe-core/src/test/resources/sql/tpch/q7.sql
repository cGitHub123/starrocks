[sql]
select
    supp_nation,
    cust_nation,
    l_year,
    sum(volume) as revenue
from
    (
        select
            n1.n_name as supp_nation,
            n2.n_name as cust_nation,
            extract(year from l_shipdate) as l_year,
            l_extendedprice * (1 - l_discount) as volume
        from
            supplier,
            lineitem,
            orders,
            customer,
            nation n1,
            nation n2
        where
                s_suppkey = l_suppkey
          and o_orderkey = l_orderkey
          and c_custkey = o_custkey
          and s_nationkey = n1.n_nationkey
          and c_nationkey = n2.n_nationkey
          and (
                (n1.n_name = 'CANADA' and n2.n_name = 'IRAN')
                or (n1.n_name = 'IRAN' and n2.n_name = 'CANADA')
            )
          and l_shipdate between date '1995-01-01' and date '1996-12-31'
    ) as shipping
group by
    supp_nation,
    cust_nation,
    l_year
order by
    supp_nation,
    cust_nation,
    l_year ;
[result]
TOP-N (order by [[46: N_NAME ASC NULLS FIRST, 51: N_NAME ASC NULLS FIRST, 55: year ASC NULLS FIRST]])
    TOP-N (order by [[46: N_NAME ASC NULLS FIRST, 51: N_NAME ASC NULLS FIRST, 55: year ASC NULLS FIRST]])
        AGGREGATE ([GLOBAL] aggregate [{57: sum(56: expr)=sum(57: sum(56: expr))}] group by [[46: N_NAME, 51: N_NAME, 55: year]] having [null]
            EXCHANGE SHUFFLE[46, 51, 55]
                AGGREGATE ([LOCAL] aggregate [{57: sum(56: expr)=sum(56: expr)}] group by [[46: N_NAME, 51: N_NAME, 55: year]] having [null]
                    INNER JOIN (join-predicate [1: S_SUPPKEY = 11: L_SUPPKEY AND 4: S_NATIONKEY = 45: N_NATIONKEY] post-join-predicate [null])
                        SCAN (columns[1: S_SUPPKEY, 4: S_NATIONKEY] predicate[null])
                        EXCHANGE SHUFFLE[11]
                            INNER JOIN (join-predicate [46: N_NAME = CANADA AND 51: N_NAME = IRAN OR 46: N_NAME = IRAN AND 51: N_NAME = CANADA] post-join-predicate [null])
                                SCAN (columns[45: N_NATIONKEY, 46: N_NAME] predicate[46: N_NAME IN (CANADA, IRAN)])
                                EXCHANGE BROADCAST
                                    INNER JOIN (join-predicate [50: N_NATIONKEY = 39: C_NATIONKEY] post-join-predicate [null])
                                        SCAN (columns[50: N_NATIONKEY, 51: N_NAME] predicate[51: N_NAME IN (IRAN, CANADA)])
                                        EXCHANGE SHUFFLE[39]
                                            INNER JOIN (join-predicate [36: C_CUSTKEY = 27: O_CUSTKEY] post-join-predicate [null])
                                                SCAN (columns[36: C_CUSTKEY, 39: C_NATIONKEY] predicate[null])
                                                EXCHANGE SHUFFLE[27]
                                                    INNER JOIN (join-predicate [26: O_ORDERKEY = 9: L_ORDERKEY] post-join-predicate [null])
                                                        SCAN (columns[26: O_ORDERKEY, 27: O_CUSTKEY] predicate[null])
                                                        EXCHANGE SHUFFLE[9]
                                                            SCAN (columns[19: L_SHIPDATE, 9: L_ORDERKEY, 11: L_SUPPKEY, 14: L_EXTENDEDPRICE, 15: L_DISCOUNT] predicate[19: L_SHIPDATE >= 1995-01-01 AND 19: L_SHIPDATE <= 1996-12-31])
[end]

