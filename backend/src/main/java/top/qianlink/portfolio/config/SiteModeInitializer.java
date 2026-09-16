package top.qianlink.portfolio.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.qianlink.portfolio.service.SiteSnapshotService;

/**
 * 启动时把「静态/动态」模式重新落地一次。
 *
 * <p>为什么需要：每次部署都会用新的 `index.html` 覆盖站点目录，而静态模式需要的是
 * 「index.html + 一行模式标记」的副本。不重放一遍的话，部署完模式就悄悄退回动态了——
 * 后台显示还是静态，实际却在查库，这种不一致最难查。
 *
 * <p>顺序放在种子数据之后（本站的种子 Seeder 是默认顺序，这里用 @Order 排在后面），
 * 否则首次启动时快照会是空的。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class SiteModeInitializer implements ApplicationRunner {

    private final SiteSnapshotService snapshotService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String mode = snapshotService.currentMode();
            if (SiteSnapshotService.MODE_STATIC.equals(mode)) {
                snapshotService.rebuildSnapshot();
                log.info("启动时重新落地静态模式：已按当前数据库内容生成快照");
            }
        } catch (Exception e) {
            // 起不来也不能因为这一步炸掉：模式没落地最多是回到动态（慢一点），不影响可用
            log.warn("启动时落地站点模式失败：{}", e.toString());
        }
    }
}
