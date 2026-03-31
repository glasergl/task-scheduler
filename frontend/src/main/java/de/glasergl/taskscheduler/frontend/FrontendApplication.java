package de.glasergl.taskscheduler.frontend;

import de.glasergl.taskscheduler.frontend.client.SchedulerClient;
import de.glasergl.taskscheduler.frontend.ui.SchedulerDashboard;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;

public final class FrontendApplication {
    private FrontendApplication() {
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Falling back to the default look and feel is fine.
        }

        SwingUtilities.invokeLater(() -> {
            SchedulerDashboard schedulerDashboard = new SchedulerDashboard(new SchedulerClient());
            applyWindowIcon(schedulerDashboard);
            schedulerDashboard.setVisible(true);
        });
    }

    private static void applyWindowIcon(SchedulerDashboard schedulerDashboard) {
        try (InputStream inputStream = FrontendApplication.class.getResourceAsStream("/branding/task-scheduler-icon.png")) {
            if (inputStream == null) {
                return;
            }

            Image image = ImageIO.read(inputStream);
            if (image != null) {
                schedulerDashboard.setIconImage(image);
            }
        } catch (IOException ignored) {
            // If the icon cannot be loaded, the window can still start normally.
        }
    }
}
