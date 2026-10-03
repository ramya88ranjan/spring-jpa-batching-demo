package demo;

import jakarta.persistence.*;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Counts JDBC statement executions for Spring Data JPA saveAll() of 10,000 new entities:
 *   A) SEQUENCE allocationSize=1, no JDBC batching   (a common enterprise baseline)
 *   B) allocationSize=50 + hibernate.jdbc.batch_size=50
 * "Execution" = one JDBC execute/executeBatch observed by datasource-proxy.
 * Run:  mvn -q spring-boot:run        (JDK 17+)
 */
@SpringBootApplication
@EnableJpaRepositories(considerNestedRepositories = true)
public class Main {

    static final int ROWS = 10_000;

    // ---------- entities (allocationSize is an annotation, hence one entity per configuration) ----------
    @Entity
    static class BaselineOrder {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "base_seq")
        @SequenceGenerator(name = "base_seq", sequenceName = "BASE_SEQ", allocationSize = 1)
        Long id;
        String item;
        protected BaselineOrder() {}
        BaselineOrder(String item) { this.item = item; }
    }

    @Entity
    static class BatchedOrder {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "batch_seq")
        @SequenceGenerator(name = "batch_seq", sequenceName = "BATCH_SEQ", allocationSize = 50)
        Long id;
        String item;
        protected BatchedOrder() {}
        BatchedOrder(String item) { this.item = item; }
    }

    interface BaselineRepo extends JpaRepository<BaselineOrder, Long> {}
    interface BatchedRepo  extends JpaRepository<BatchedOrder, Long> {}

    // ---------- statement counting ----------
    static class Counter implements QueryExecutionListener {
        volatile boolean armed;
        long seqFetches, insertExecs, rowsSent, otherExecs;
        @Override public void beforeQuery(ExecutionInfo e, List<QueryInfo> q) {}
        @Override public void afterQuery(ExecutionInfo e, List<QueryInfo> q) {
            if (!armed) return;
            String sql = q.get(0).getQuery().toLowerCase();
            int n = e.isBatch() ? e.getBatchSize() : 1;
            if (sql.contains("next value") || sql.contains("nextval")) seqFetches++;
            else if (sql.startsWith("insert")) { insertExecs++; rowsSent += n; }
            else otherExecs++;
        }
    }

    /** Fresh counter per run; the proxy wrapper below captures the current one at context startup. */
    static Counter counter;

    @Bean
    static BeanPostProcessor dataSourceProxyWrapper() {
        final Counter c = counter;
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof ProxyDataSource)) {
                    return ProxyDataSourceBuilder.create(ds).listener(c).build();
                }
                return bean;
            }
        };
    }

    static ConfigurableApplicationContext start(String db, boolean batching) {
        SpringApplicationBuilder b = new SpringApplicationBuilder(Main.class).properties(
                "spring.datasource.url=jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create",
                "spring.main.banner-mode=off",
                "logging.level.root=warn");
        if (batching) {
            b.properties("spring.jpa.properties.hibernate.jdbc.batch_size=50",
                         "spring.jpa.properties.hibernate.order_inserts=true");
        }
        return b.run();
    }

    static void report(String label, Runnable saveAll, LongSupplier countRows, Counter c) {
        c.armed = true;                               // DDL is done; count only the save
        long t0 = System.nanoTime();
        saveAll.run();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        c.armed = false;                              // stop counting before the verification query
        long committed = countRows.getAsLong();
        if (committed != ROWS || c.rowsSent != ROWS) {
            throw new IllegalStateException(
                "Expected " + ROWS + " rows; committed=" + committed
                + ", submitted=" + c.rowsSent);
        }
        long total = c.seqFetches + c.insertExecs + c.otherExecs;
        System.out.printf("%n%s%n", label);
        System.out.printf("  sequence fetches       : %,d%n", c.seqFetches);
        System.out.printf("  INSERT executions      : %,d   (rows sent: %,d)%n", c.insertExecs, c.rowsSent);
        System.out.printf("  other executions       : %,d%n", c.otherExecs);
        System.out.printf("  TOTAL JDBC executions  : %,d%n", total);
        System.out.printf("  rows committed (count) : %,d%n", committed);
        System.out.printf("  wall time              : %,d ms (in-memory H2 — not a latency benchmark)%n", ms);
    }

    public static void main(String[] args) {
        System.out.printf("Bulk-saving %,d rows via Spring Data JPA saveAll(), two configurations:%n", ROWS);

        counter = new Counter();
        try (ConfigurableApplicationContext ctx = start("base", false)) {
            BaselineRepo repo = ctx.getBean(BaselineRepo.class);
            List<BaselineOrder> rows = new ArrayList<>(ROWS);
            for (int i = 1; i <= ROWS; i++) rows.add(new BaselineOrder("item-" + i));
            report("A) BASELINE — allocationSize=1, no JDBC batching",
                   () -> repo.saveAll(rows), repo::count, counter);
        }

        counter = new Counter();
        try (ConfigurableApplicationContext ctx = start("batch", true)) {
            BatchedRepo repo = ctx.getBean(BatchedRepo.class);
            List<BatchedOrder> rows = new ArrayList<>(ROWS);
            for (int i = 1; i <= ROWS; i++) rows.add(new BatchedOrder("item-" + i));
            report("B) BATCHED  — allocationSize=50, hibernate.jdbc.batch_size=50",
                   () -> repo.saveAll(rows), repo::count, counter);
        }
    }
}
