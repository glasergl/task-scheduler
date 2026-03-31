package de.glasergl.taskscheduler.backend.service;

public final class CronExpressionNormalizer {
    private CronExpressionNormalizer() {
    }

    public static String normalize(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("Cron expression must not be blank.");
        }

        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length == 5) {
            return normalizeFiveFieldCron(fields);
        }

        if (fields.length == 6 || fields.length == 7) {
            return String.join(" ", fields);
        }

        throw new IllegalArgumentException("Cron expression must have 5, 6, or 7 fields.");
    }

    private static String normalizeFiveFieldCron(String[] fields) {
        String minute = fields[0];
        String hour = fields[1];
        String dayOfMonth = fields[2];
        String month = fields[3];
        String dayOfWeek = fields[4];

        if ("*".equals(dayOfMonth) && "*".equals(dayOfWeek)) {
            return String.format("0 %s %s * %s ?", minute, hour, month);
        }

        if ("*".equals(dayOfMonth)) {
            return String.format("0 %s %s ? %s %s", minute, hour, month, dayOfWeek);
        }

        if ("*".equals(dayOfWeek)) {
            return String.format("0 %s %s %s %s ?", minute, hour, dayOfMonth, month);
        }

        throw new IllegalArgumentException(
                "5-field cron expressions that specify both day-of-month and day-of-week are ambiguous for Quartz. " +
                        "Use a 6-field Quartz cron with ? in one of those positions."
        );
    }
}
