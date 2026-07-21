package com.iplms;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets; // Import StandardCharsets
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ConverterGUI extends JFrame {

    private JTextArea consoleOutputArea;
    private JButton runButton;
    private JButton stopButton;

    private ScheduledExecutorService scheduler;

    public ConverterGUI() throws UnsupportedEncodingException {
        setTitle("IPLMS Hybrid Converter");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null); // Center the window

        initComponents();
        redirectSystemOutput();

        // Moved initial setup here to run once when GUI starts
        System.out.println(">> [System 환경 정보] 현재 실행 경로 (User Dir): " + System.getProperty("user.dir"));
        ConverterMain.loadProperties(); // Load properties once at startup
        System.out.println(">> [IPLMS Hybrid Converter] GUI 애플리케이션 시작.");
    }

    private void initComponents() {
        // Console Output Area
        consoleOutputArea = new JTextArea();
        consoleOutputArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(consoleOutputArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Buttons Panel
        JPanel buttonPanel = new JPanel();
        runButton = new JButton("실행");
        stopButton = new JButton("종료");

        buttonPanel.add(runButton);
        buttonPanel.add(stopButton);

        // Add components to JFrame
        add(buttonPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // Action Listeners
        runButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startService();
            }
        });

        stopButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopService();
            }
        });

        // Initial button state
        stopButton.setEnabled(false);
    }

    private void redirectSystemOutput() throws UnsupportedEncodingException {
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                SwingUtilities.invokeLater(() -> {
                    consoleOutputArea.append(String.valueOf((char) b));
                    consoleOutputArea.setCaretPosition(consoleOutputArea.getDocument().getLength());
                });
            }

            @Override
            public void write(byte[] b, int off, int len) {
                SwingUtilities.invokeLater(() -> {
                    // Use UTF-8 for correct character display
                    consoleOutputArea.append(new String(b, off, len, StandardCharsets.UTF_8));
                    consoleOutputArea.setCaretPosition(consoleOutputArea.getDocument().getLength());
                });
            }
        };

        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.toString())); // Specify UTF-8 for PrintStream
        System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8.toString())); // Specify UTF-8 for PrintStream
    }

    private void startService() {
        if (scheduler != null && !scheduler.isShutdown()) {
            System.out.println("서비스가 이미 실행 중입니다.");
            return;
        }

        System.out.println(">> [IPLMS Hybrid Converter] 서비스 실행 요청...");
        runButton.setEnabled(false);
        stopButton.setEnabled(true);

        // Properties are now loaded in the constructor, no need to load again here.

        scheduler = Executors.newSingleThreadScheduledExecutor();
        int daemonIntervalMinutes = ConverterMain.getDaemonIntervalMinutes(); // Get interval from ConverterMain
        System.out.println(">> [IPLMS Hybrid Converter] 데몬 모드로 시작합니다. 실행 주기: " + daemonIntervalMinutes + "분");

        scheduler.scheduleAtFixedRate(ConverterMain::runConversionCycle, 0, daemonIntervalMinutes, TimeUnit.MINUTES);
    }

    private void stopService() {
        if (scheduler != null && !scheduler.isShutdown()) {
            System.out.println(">> [IPLMS Hybrid Converter] 서비스 종료 요청...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    System.err.println("ERROR: 서비스가 강제로 종료되었습니다.");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                System.err.println("ERROR: 서비스 종료 중 인터럽트 발생, 강제 종료되었습니다.");
                Thread.currentThread().interrupt();
            }
            System.out.println(">> [IPLMS Hybrid Converter] 서비스가 종료되었습니다.");
        } else {
            System.out.println("서비스가 실행 중이 아닙니다.");
        }
        runButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new ConverterGUI().setVisible(true);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
