package top.qianlink.portfolio.stats;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 埋点写库的唯一入口：单线程 + 有界队列。
 *
 * <p>为什么必须异步：埋点每个请求都要写一次库，同步写等于给每个接口都加一次磁盘 IO。
 * 统计少记几条无所谓，把站点拖慢不行。所以队列满了就直接丢，只累加一个丢弃计数
 * （看板上的「丢弃数」就是它），绝不阻塞业务线程。
 *
 * <p>为什么单线程：H2 是单文件库，多线程写反而更容易撞锁。单线程顺序写既够用又稳。
 *
 * <p>关闭时会尽力把队列里剩下的写完（最多等 5 秒），不然最后几秒的埋点会白丢。
 */
@Slf4j
@Component
public class AsyncLogWriter {

    /** 队列容量。按日均几十 PV 的量级，2000 条足够吸收任何突发 */
    private static final int QUEUE_CAPACITY = 2000;

    /** 关闭时最多再等多久把队列排空 */
    private static final long DRAIN_TIMEOUT_MS = 5000;

    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicLong droppedCount = new AtomicLong();

    private volatile boolean running = true;

    private Thread worker;

    @PostConstruct
    void start() {
        worker = new Thread(this::loop, "stats-log-writer");
        // 守护线程：万一没走完关闭流程，也不该拦住 JVM 退出
        worker.setDaemon(true);
        worker.start();
        log.info("埋点异步写库线程已启动，队列容量 {}", QUEUE_CAPACITY);
    }

    private void loop() {
        while (true) {
            try {
                Runnable task = queue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) {
                    // 队列空了且已经要求关闭，可以直接退出
                    if (!running) {
                        return;
                    }
                    continue;
                }
                runSafely(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            // 单条写失败不能拖垮写线程，否则后面所有埋点都写不进去了
            log.warn("埋点入库失败：{}", e.toString());
        }
    }

    /** 投递一条写库任务。队列满就丢弃并计数，永远不阻塞调用方。 */
    public void submit(Runnable task) {
        if (!running) {
            return;
        }
        if (!queue.offer(task)) {
            droppedCount.incrementAndGet();
        }
    }

    /** 被丢弃的埋点数，看板上展示，用来判断队列该不该调大 */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /** 队列里还没落库的条数 */
    public int getPendingCount() {
        return queue.size();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 排空后 poll 会拿到 null，循环里看到 running=false 就自己退了
        worker.interrupt();
        int left = queue.size();
        if (left > 0) {
            log.warn("关闭时仍有 {} 条埋点没写完，已放弃", left);
        } else {
            log.info("埋点写库线程已排空退出");
        }
    }
}
