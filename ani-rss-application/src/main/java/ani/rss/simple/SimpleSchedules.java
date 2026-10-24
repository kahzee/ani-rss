package ani.rss.simple;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SimpleSchedules {
    @Bean(name="taskScheduler") ThreadPoolTaskScheduler tasks(){return scheduler(4,"scheduled-");}
    @Bean(name="simpleProgressScheduler") ThreadPoolTaskScheduler progress(){return scheduler(1,"simple-progress-");}
    private ThreadPoolTaskScheduler scheduler(int size,String prefix){
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(size);scheduler.setThreadNamePrefix(prefix);scheduler.setDaemon(true);return scheduler;
    }
}
