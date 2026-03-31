package de.glasergl.taskscheduler.backend.job;

import de.glasergl.taskscheduler.backend.service.TaskSchedulerService;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;

import java.util.UUID;

public final class CommandExecutionJob implements Job {
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            TaskSchedulerService taskSchedulerService =
                    (TaskSchedulerService) context.getScheduler().getContext().get(TaskSchedulerService.SCHEDULER_CONTEXT_KEY);
            JobDataMap jobDataMap = context.getMergedJobDataMap();
            UUID taskId = UUID.fromString(jobDataMap.getString("taskId"));
            taskSchedulerService.executeTask(taskId);
        } catch (SchedulerException exception) {
            throw new JobExecutionException("Unable to resolve task scheduler service.", exception);
        }
    }
}

