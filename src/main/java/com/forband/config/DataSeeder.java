package com.forband.config;

import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SectionMapper sectionMapper;

    public DataSeeder(SectionMapper sectionMapper) {
        this.sectionMapper = sectionMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (sectionMapper.selectCount(null) > 0) {
            return;
        }
        String[] names = {"吹管", "弹拨", "拉弦", "打击乐"};
        for (int i = 0; i < names.length; i++) {
            Section section = new Section();
            section.setName(names[i]);
            section.setOrderNo(i + 1);
            sectionMapper.insert(section);
        }
        log.info("首次启动，已写入 4 个初始声部：吹管 / 弹拨 / 拉弦 / 打击乐");
    }
}
