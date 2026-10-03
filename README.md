# Spring Data JPA bulk-insert batching — measured

A minimal, reproducible demo of how two configuration changes cut the JDBC
execution count of a `saveAll()` bulk insert by ~50×.

It saves **10,000 new entities** through Spring Data JPA's `saveAll()` under two
configurations and counts every JDBC execution with
[datasource-proxy](https://github.com/jdbc-observations/datasource-proxy):

| Configuration | Sequence fetches | INSERT executions | Total JDBC executions |
|---|---:|---:|---:|
| A) Baseline — `allocationSize=1`, no batching | 10,000 | 10,000 | **20,000** |
| B) Batched — `allocationSize=50`, `batch_size=50` | 201 | 200 (50 rows each) | **401** |

**20,000 → 401 = ~50× fewer JDBC execution calls in this demo.** Both runs
commit all 10,000 rows (asserted). The 201st sequence fetch is the pooled
optimizer's one-time warm-up call.

Measured on Spring Boot 3.5.16 · Hibernate 6.6 · H2 (in-memory) · JDK 17.
Statement executions are what the JDBC layer sees; network behavior is
driver-dependent — run it on your own stack and compare.

## The two settings

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
```

```java
@SequenceGenerator(name = "order_seq", sequenceName = "ORDER_SEQ",
                   allocationSize = 50)   // must match the sequence's INCREMENT BY
```

Gotchas demonstrated or asserted in the code:

- `GenerationType.IDENTITY` disables JDBC insert batching entirely — the demo
  uses `SEQUENCE`.
- `allocationSize` must match the database sequence's `INCREMENT BY`; modern
  Hibernate (5.4+) fails fast at startup on a mismatch. On a live system, ship
  the `ALTER SEQUENCE` and the entity change as one coordinated migration.
- `allocationSize` is mapping metadata — it can't be changed through an
  ordinary Spring Boot property, which is why the demo uses one entity class
  per configuration.

## Run it

Requires JDK 17+ and Maven:

```
mvn -q spring-boot:run
```

Expect two result blocks (A and B). Both must print `rows committed: 10,000` —
the demo throws otherwise. Exact counts can vary by a handful of executions
(optimizer warm-up, pool housekeeping); whatever it prints on your machine are
your numbers.

## Context

Written for a LinkedIn post on why `saveAll()` alone doesn't guarantee
batching: *(link to post)*
