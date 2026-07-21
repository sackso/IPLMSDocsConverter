package com.iplms;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ConverterGUI extends JFrame {

    private JTextArea consoleOutputArea;
    private JButton runButton;
    private JButton stopButton;

    private ScheduledExecutorService scheduler;

    public ConverterGUI() { // Removed UnsupportedEncodingException from constructor signature
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

    private void redirectSystemOutput() { // Removed UnsupportedEncodingException from method signature
        PipedOutputStream pos = new PipedOutputStream();
        try {
            System.setOut(new PrintStream(pos, true, StandardCharsets.UTF_8.toString()));
            System.setErr(new PrintStream(pos, true, StandardCharsets.UTF_8.toString()));
        } catch (IOException e) { // Changed to IOException as PrintStream constructor can throw it
            System.err.println("ERROR: Failed to set PrintStream encoding: " + e.getMessage());
        }


        try {
            PipedInputStream pis = new PipedInputStream(pos);
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pis, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String finalLine = line;
                        SwingUtilities.invokeLater(() -> {
                            consoleOutputArea.append(finalLine + "\n");
                            consoleOutputArea.setCaretPosition(consoleOutputArea.getDocument().getLength());
                        });
                    }
                } catch (IOException e) {
                    // This error will be printed to the redirected System.err
                    System.err.println("ERROR: Console redirection thread error: " + e.getMessage());
                }
            }).start();
        } catch (IOException e) {
            System.err.println("ERROR: Failed to redirect console output: " + e.getMessage());
        }
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
            // Removed try-catch for UnsupportedEncodingException as it's no longer thrown
            new ConverterGUI().setVisible(true);
        });
    }
}
