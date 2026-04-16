package de.glasergl.taskscheduler.frontend.ui;

import de.glasergl.taskscheduler.frontend.client.SchedulerClient;
import de.glasergl.taskscheduler.frontend.model.ApiExecutionRecord;
import de.glasergl.taskscheduler.frontend.model.ApiRunningTask;
import de.glasergl.taskscheduler.frontend.model.ApiSchedulerOverview;
import de.glasergl.taskscheduler.frontend.model.ApiTaskSummary;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SchedulerDashboard extends JFrame {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SchedulerClient schedulerClient;
    private final ExecutorService requestExecutor;
    private final Timer refreshTimer;

    private final JTextField baseUrlField = new JTextField("http://127.0.0.1:52398", 28);
    private final JTextField cronField = new JTextField(24);
    private final JTextField commandField = new JTextField(48);
    private final JButton createButton = new JButton("Create Task");
    private final JButton updateButton = new JButton("Update Task");
    private final JButton loadSelectedButton = new JButton("Load Selected Task");
    private final JButton cancelEditButton = new JButton("Cancel Edit");
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton deleteButton = new JButton("Delete Selected Task");
    private final JLabel editModeLabel = new JLabel("Create mode", SwingConstants.LEFT);
    private final JLabel statusLabel = new JLabel("Waiting for backend...", SwingConstants.LEFT);

    private final DefaultTableModel scheduledTableModel = new NonEditableTableModel(
            new String[]{"Command", "Cron", "Next Run (Local)", "Previous Run (Local)", "Last Status"}, 0);
    private final DefaultTableModel runningTableModel = new NonEditableTableModel(
            new String[]{"Execution UUID", "Task UUID", "Command", "Started At", "PID"}, 0);
    private final DefaultTableModel historyTableModel = new NonEditableTableModel(
            new String[]{"Finished At", "Task UUID", "Command", "Exit Code", "Status", "Message"}, 0);

    private final JTable scheduledTable = createTooltipTable(scheduledTableModel);
    private final JTable runningTable = createTooltipTable(runningTableModel);
    private final JTable historyTable = createTooltipTable(historyTableModel);

    private volatile boolean requestInFlight;
    private List<ApiTaskSummary> currentScheduledTasks = List.of();
    private UUID editingTaskId;

    public SchedulerDashboard(SchedulerClient schedulerClient) {
        super("Task Scheduler");
        this.schedulerClient = schedulerClient;
        this.requestExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "frontend-api-client");
            thread.setDaemon(true);
            return thread;
        });
        this.refreshTimer = new Timer(5_000, event -> refreshOverview(false));

        cronField.setToolTipText("Quartz cron or standard 5-field cron. Five fields are expanded with leading seconds.");
        commandField.setToolTipText("Example: ping 127.0.0.1 -n 2");

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(12, 12));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(buildControlPanel(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        scheduledTable.setAutoCreateRowSorter(true);
        runningTable.setAutoCreateRowSorter(true);
        historyTable.setAutoCreateRowSorter(true);
        scheduledTable.setFillsViewportHeight(true);
        runningTable.setFillsViewportHeight(true);
        historyTable.setFillsViewportHeight(true);
        scheduledTable.getColumnModel().getColumn(0).setPreferredWidth(520);
        scheduledTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        scheduledTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        scheduledTable.getColumnModel().getColumn(3).setPreferredWidth(150);
        scheduledTable.getColumnModel().getColumn(4).setPreferredWidth(120);

        createButton.addActionListener(event -> createTask());
        updateButton.addActionListener(event -> updateTask());
        loadSelectedButton.addActionListener(event -> loadSelectedTaskForEditing());
        cancelEditButton.addActionListener(event -> clearEditMode());
        refreshButton.addActionListener(event -> refreshOverview(true));
        deleteButton.addActionListener(event -> deleteSelectedTask());
        refreshActionButtons();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                refreshTimer.stop();
                requestExecutor.shutdownNow();
            }
        });

        setSize(1260, 720);
        setLocationRelativeTo(null);
        refreshTimer.start();
        refreshOverview(true);
    }

    private JPanel buildControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        constraints.gridx = 0;
        constraints.gridy = 0;
        panel.add(new JLabel("Backend URL"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0;
        panel.add(baseUrlField, constraints);

        constraints.gridx = 2;
        constraints.weightx = 0.0;
        panel.add(refreshButton, constraints);

        constraints.gridx = 0;
        constraints.gridy = 1;
        panel.add(new JLabel("Command"), constraints);

        constraints.gridx = 1;
        constraints.gridwidth = 2;
        panel.add(commandField, constraints);

        constraints.gridx = 0;
        constraints.gridy = 2;
        constraints.gridwidth = 1;
        panel.add(new JLabel("Cron"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0;
        panel.add(cronField, constraints);

        constraints.gridx = 2;
        constraints.weightx = 0.0;
        panel.add(createButton, constraints);

        constraints.gridx = 0;
        constraints.gridy = 3;
        constraints.gridwidth = 1;
        panel.add(new JLabel("Edit"), constraints);

        JPanel editActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        editActions.add(editModeLabel);
        editActions.add(loadSelectedButton);
        editActions.add(updateButton);
        editActions.add(cancelEditButton);

        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1.0;
        panel.add(editActions, constraints);

        constraints.gridx = 1;
        constraints.gridy = 4;
        constraints.gridwidth = 2;
        panel.add(new JLabel("Note: the cron is interpreted in your current local timezone when you create the task, then anchored to an invariant timezone so it stays consistent later."), constraints);

        return panel;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Scheduled Tasks", wrapScheduledPanel());
        tabbedPane.addTab("Running Tasks", new JScrollPane(runningTable));
        tabbedPane.addTab("Recent Executions", new JScrollPane(historyTable));
        return tabbedPane;
    }

    private JPanel wrapScheduledPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JScrollPane(scheduledTable), BorderLayout.CENTER);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actionsPanel.add(deleteButton);
        panel.add(actionsPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void createTask() {
        if (requestInFlight) {
            setStatus("Another request is still running.");
            return;
        }

        String cronExpression = cronField.getText().trim();
        String command = commandField.getText().trim();
        if (cronExpression.isBlank() || command.isBlank()) {
            setStatus("Cron and command are required.");
            return;
        }

        requestInFlight = true;
        setControlsEnabled(false);
        setStatus("Creating task...");

        CompletableFuture.runAsync(() -> {
            try {
                schedulerClient.createTask(baseUrlField.getText(), cronExpression, command);
                SwingUtilities.invokeLater(() -> {
                    cronField.setText("");
                    commandField.setText("");
                    setStatus("Task created.");
                });
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, requestExecutor).whenComplete((ignored, throwable) -> SwingUtilities.invokeLater(() -> {
            requestInFlight = false;
            setControlsEnabled(true);
            if (throwable != null) {
                setStatus("Create failed: " + rootMessage(throwable));
                return;
            }
            refreshOverview(false);
        }));
    }

    private void loadSelectedTaskForEditing() {
        int selectedRow = scheduledTable.getSelectedRow();
        if (selectedRow < 0) {
            setStatus("Select a scheduled task first.");
            return;
        }

        int modelRow = scheduledTable.convertRowIndexToModel(selectedRow);
        ApiTaskSummary selectedTask = currentScheduledTasks.get(modelRow);
        editingTaskId = selectedTask.id();
        commandField.setText(selectedTask.command());
        cronField.setText(selectedTask.cronExpression());
        updateEditModeLabel(selectedTask);
        refreshActionButtons();
        setStatus("Loaded selected task into the form.");
    }

    private void updateTask() {
        if (requestInFlight) {
            setStatus("Another request is still running.");
            return;
        }

        if (editingTaskId == null) {
            setStatus("Load a scheduled task first.");
            return;
        }

        String cronExpression = cronField.getText().trim();
        String command = commandField.getText().trim();
        if (cronExpression.isBlank() || command.isBlank()) {
            setStatus("Cron and command are required.");
            return;
        }

        requestInFlight = true;
        setControlsEnabled(false);
        setStatus("Updating task...");

        CompletableFuture.runAsync(() -> {
            try {
                schedulerClient.updateTask(baseUrlField.getText(), editingTaskId, cronExpression, command);
                SwingUtilities.invokeLater(() -> {
                    clearEditMode();
                    setStatus("Task updated.");
                });
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, requestExecutor).whenComplete((ignored, throwable) -> SwingUtilities.invokeLater(() -> {
            requestInFlight = false;
            setControlsEnabled(true);
            if (throwable != null) {
                setStatus("Update failed: " + rootMessage(throwable));
                return;
            }
            refreshOverview(false);
        }));
    }

    private void clearEditMode() {
        editingTaskId = null;
        cronField.setText("");
        commandField.setText("");
        editModeLabel.setText("Create mode");
        refreshActionButtons();
    }

    private void deleteSelectedTask() {
        if (requestInFlight) {
            setStatus("Another request is still running.");
            return;
        }

        int selectedRow = scheduledTable.getSelectedRow();
        if (selectedRow < 0) {
            setStatus("Select a scheduled task first.");
            return;
        }

        int modelRow = scheduledTable.convertRowIndexToModel(selectedRow);
        ApiTaskSummary selectedTask = currentScheduledTasks.get(modelRow);
        int confirmation = JOptionPane.showConfirmDialog(
                this,
                "Delete task " + selectedTask.id() + "?",
                "Delete Task",
                JOptionPane.YES_NO_OPTION
        );

        if (confirmation != JOptionPane.YES_OPTION) {
            return;
        }

        requestInFlight = true;
        setControlsEnabled(false);
        setStatus("Deleting task...");

        CompletableFuture.runAsync(() -> {
            try {
                schedulerClient.deleteTask(baseUrlField.getText(), selectedTask.id());
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, requestExecutor).whenComplete((ignored, throwable) -> SwingUtilities.invokeLater(() -> {
            requestInFlight = false;
            setControlsEnabled(true);
            if (throwable != null) {
                setStatus("Delete failed: " + rootMessage(throwable));
                return;
            }
            setStatus("Task deleted.");
            refreshOverview(false);
        }));
    }

    private void refreshOverview(boolean userInitiated) {
        if (requestInFlight) {
            if (userInitiated) {
                setStatus("Another request is still running.");
            }
            return;
        }

        requestInFlight = true;
        setControlsEnabled(false);
        setStatus(userInitiated ? "Refreshing..." : "Refreshing in background...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return schedulerClient.fetchOverview(baseUrlField.getText());
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, requestExecutor).whenComplete((overview, throwable) -> SwingUtilities.invokeLater(() -> {
            requestInFlight = false;
            setControlsEnabled(true);

            if (throwable != null) {
                setStatus("Refresh failed: " + rootMessage(throwable));
                return;
            }

            updateTables(overview);
            setStatus("Last refreshed at " + TIMESTAMP_FORMATTER.format(Instant.now()));
        }));
    }

    private void updateTables(ApiSchedulerOverview overview) {
        currentScheduledTasks = overview.scheduledTasks();
        replaceRows(scheduledTableModel, overview.scheduledTasks().stream()
                .map(task -> new Object[]{
                        task.command(),
                        task.cronExpression(),
                        formatInstant(task.nextRunAt()),
                        formatInstant(task.previousRunAt()),
                        task.lastStatus() == null ? "" : task.lastStatus()
                })
                .toList());

        replaceRows(runningTableModel, overview.runningTasks().stream()
                .map(task -> new Object[]{
                        task.executionId(),
                        task.taskId(),
                        task.command(),
                        formatInstant(task.startedAt()),
                        task.processId() == null ? "" : task.processId()
                })
                .toList());

        replaceRows(historyTableModel, overview.recentExecutions().stream()
                .map(record -> new Object[]{
                        formatInstant(record.finishedAt()),
                        record.taskId(),
                        record.command(),
                        record.exitCode() == null ? "" : record.exitCode(),
                        record.status(),
                        record.message()
                })
                .toList());
    }

    private void replaceRows(DefaultTableModel tableModel, List<Object[]> rows) {
        tableModel.setRowCount(0);
        for (Object[] row : rows) {
            tableModel.addRow(row);
        }
    }

    private void setControlsEnabled(boolean enabled) {
        createButton.setEnabled(enabled && editingTaskId == null);
        updateButton.setEnabled(enabled && editingTaskId != null);
        loadSelectedButton.setEnabled(enabled);
        cancelEditButton.setEnabled(enabled && editingTaskId != null);
        refreshButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
    }

    private void updateEditModeLabel(ApiTaskSummary task) {
        editModeLabel.setText("Editing " + task.id());
    }

    private void refreshActionButtons() {
        setControlsEnabled(!requestInFlight);
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    private String formatInstant(Instant instant) {
        return instant == null ? "" : TIMESTAMP_FORMATTER.format(instant);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private JTable createTooltipTable(DefaultTableModel tableModel) {
        return new JTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int column = columnAtPoint(event.getPoint());
                if (row < 0 || column < 0) {
                    return null;
                }

                Object value = getValueAt(row, column);
                if (value == null) {
                    return null;
                }

                String text = value.toString().trim();
                return text.isEmpty() ? null : text;
            }
        };
    }

    private static final class NonEditableTableModel extends DefaultTableModel {
        private NonEditableTableModel(String[] columnNames, int rowCount) {
            super(columnNames, rowCount);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
