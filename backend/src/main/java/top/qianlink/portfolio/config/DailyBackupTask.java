package top.qianlink.portfolio.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 每天把数据库和图片打成一个 zip，保留最近若干份。
 *
 * <p><b>为什么备份逻辑写在应用里，而不是服务器上的 cron/tar</b>：
 * 数据库是 H2 单文件库，进程开着的时候直接 cp 那个 .mv.db，可能拿到一个写了一半的
 * 文件——备份看起来成功了，真要用的时候才发现是坏的，这比没有备份更糟。
 * H2 自带在线备份命令（BACKUP TO，拿到的是一致快照），但它需要能连上库；
 * 应用自己就有连接池，顺手就做了，服务器上不用再装 cron、也不用额外放一份 h2 jar。
 *
 * <p>产物是 {@code backups/portfolio-<时间戳>.zip}，里面是 {@code db.zip}（H2 的快照）
 * 和 {@code files/}（上传的图片、简历）。体积很小：个人站的库和图片加起来几 MB。
 *
 * <p>失败不影响站点：异常全部吃掉只记日志。备份坏了是运维问题，
 * 不能让定时任务把主进程带崩。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyBackupTask {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final DataSource dataSource;
    private final AppProperties props;

    /** 每天凌晨 3 点。个人站这个点基本没人，压缩几 MB 文件毫无感知 */
    @Scheduled(cron = "${app.backup.cron:0 0 3 * * *}")
    public void backup() {
        AppProperties.Backup cfg = props.getBackup();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        try {
            Path dir = Paths.get(cfg.getDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(STAMP);
            Path target = dir.resolve("portfolio-" + stamp + ".zip");

            Path dbSnapshot = Files.createTempFile("portfolio-db-", ".zip");
            try {
                if (!dumpDatabase(dbSnapshot)) {
                    log.warn("数据库快照失败，本次备份跳过");
                    return;
                }
                writeArchive(target, dbSnapshot);
            } finally {
                Files.deleteIfExists(dbSnapshot);
            }

            log.info("每日备份完成：{}（{} KB）", target, Files.size(target) / 1024);
            purgeOld(dir, cfg.getKeep());
        } catch (Exception e) {
            log.error("每日备份失败（不影响站点运行）", e);
        }
    }

    /** 用 H2 自己的在线备份命令导出一致快照 */
    private boolean dumpDatabase(Path target) {
        try {
            // BACKUP TO 不会覆盖已存在的文件，先清掉上次的临时快照
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("清理旧的临时快照失败：{}", e.toString());
        }
        String sql = "BACKUP TO '" + target.toAbsolutePath().toString().replace("'", "''") + "'";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
            return Files.exists(target);
        } catch (Exception e) {
            log.warn("执行 H2 在线备份失败：{}", e.toString());
            return false;
        }
    }

    /** 把数据库快照和整个文件目录打进一个 zip */
    private void writeArchive(Path target, Path dbSnapshot) throws IOException {
        Path filesDir = Paths.get(props.getStorage().getLocal().getDir())
                .toAbsolutePath().normalize();

        try (OutputStream raw = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(raw)) {

            zip.putNextEntry(new ZipEntry("db.zip"));
            Files.copy(dbSnapshot, zip);
            zip.closeEntry();

            if (Files.isDirectory(filesDir)) {
                try (var walk = Files.walk(filesDir)) {
                    List<Path> files = walk.filter(Files::isRegularFile).toList();
                    for (Path file : files) {
                        String relative = filesDir.relativize(file).toString().replace('\\', '/');
                        zip.putNextEntry(new ZipEntry("files/" + relative));
                        try (InputStream in = Files.newInputStream(file)) {
                            in.transferTo(zip);
                        }
                        zip.closeEntry();
                    }
                }
            }

            // 顺手放一份说明，将来解包的人不用猜这是什么
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(("""
                    作品站备份
                    生成时间：%s

                    db.zip   H2 数据库快照（用 H2 自己的 BACKUP 导出，可直接还原）
                    files/   上传的图片与简历原件

                    还原方式：把 db.zip 解到后端工作目录下的 data/portfolio.mv.db 位置，
                    files/ 解到 data/files/。
                    """.formatted(LocalDateTime.now())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /** 只保留最近 keep 份 */
    private void purgeOld(Path dir, int keep) {
        int limit = keep < 1 ? 1 : keep;
        try (var stream = Files.list(dir)) {
            List<Path> old = stream
                    .filter(p -> p.getFileName().toString().startsWith("portfolio-"))
                    .filter(p -> p.getFileName().toString().endsWith(".zip"))
                    // 文件名里就是时间戳，倒序即从新到旧
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .skip(limit)
                    .toList();
            for (Path p : old) {
                Files.deleteIfExists(p);
                log.info("清理旧备份：{}", p.getFileName());
            }
        } catch (IOException e) {
            log.warn("清理旧备份失败：{}", e.toString());
        }
    }
}
