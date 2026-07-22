package com.iplms;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConverterGUI extends JFrame {

    private JTextPane consoleOutputArea; // Changed from JTextArea to JTextPane
    private StyledDocument doc; // To manage styles in JTextPane
    private JButton runButton;
    private JButton stopButton;
    private JButton openOutputButton;
    private JButton clearLogButton;

    private ScheduledExecutorService scheduler;

    // Define styles for different log types
    private SimpleAttributeSet defaultStyle;
    private SimpleAttributeSet greenStyle;
    private SimpleAttributeSet redStyle;
    private SimpleAttributeSet orangeStyle;
    private SimpleAttributeSet blueStyle;
    private SimpleAttributeSet purpleStyle;
    private SimpleAttributeSet grayStyle;

    public ConverterGUI() {
        setTitle("IPLMS Hybrid Converter");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopService();
                System.exit(0);
            }
        });

        initStyles(); // Initialize text styles
        initComponents();
        redirectSystemOutput();

        System.out.println(">> [System 환경 정보] 현재 실행 경로 (User Dir): " + System.getProperty("user.dir"));
        ConverterMain.loadProperties();
        System.out.println(">> [IPLMS Hybrid Converter] GUI 애플리케이션 시작.");
    }

    private void initStyles() {
        defaultStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(defaultStyle, Color.BLACK);

        greenStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(greenStyle, new Color(0, 128, 0)); // Dark Green
        StyleConstants.setBold(greenStyle, true);

        redStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(redStyle, Color.RED);
        StyleConstants.setBold(redStyle, true);

        orangeStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(orangeStyle, Color.ORANGE.darker()); // Darker Orange
        StyleConstants.setBold(orangeStyle, true);

        blueStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(blueStyle, Color.BLUE);
        StyleConstants.setBold(blueStyle, true);

        purpleStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(purpleStyle, new Color(128, 0, 128)); // Purple
        StyleConstants.setBold(purpleStyle, true);

        grayStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(grayStyle, Color.GRAY);
        StyleConstants.setBold(grayStyle, true);
    }

    private void initComponents() {
        consoleOutputArea = new JTextPane(); // Use JTextPane
        consoleOutputArea.setEditable(false);
        doc = consoleOutputArea.getStyledDocument(); // Get the StyledDocument
        JScrollPane scrollPane = new JScrollPane(consoleOutputArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        JPanel buttonPanel = new JPanel();
        runButton = new JButton("실행");
        stopButton = new JButton("종료");
        openOutputButton = new JButton("출력 폴더 열기");
        clearLogButton = new JButton("로그 지우기");

        buttonPanel.add(runButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(openOutputButton);
        buttonPanel.add(clearLogButton);

        add(buttonPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        runButton.addActionListener(e -> startService());
        stopButton.addActionListener(e -> stopService());
        openOutputButton.addActionListener(e -> openOutputDirectory());
        clearLogButton.addActionListener(e -> {
            try {
                doc.remove(0, doc.getLength()); // Clear the JTextPane
            } catch (BadLocationException ex) {
                System.err.println("ERROR: Failed to clear log: " + ex.getMessage());
            }
        });

        stopButton.setEnabled(false);
    }

    private void redirectSystemOutput() {
        PipedOutputStream pos = new PipedOutputStream();
        try {
            System.setOut(new PrintStream(pos, true, StandardCharsets.UTF_8.toString()));
            System.setErr(new PrintStream(pos, true, StandardCharsets.UTF_8.toString()));
        } catch (IOException e) {
            System.err.println("ERROR: Failed to set PrintStream encoding: " + e.getMessage());
        }

        try {
            PipedInputStream pis = new PipedInputStream(pos);
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pis, StandardCharsets.UTF_8))) {
                    String line;
                    Pattern pattern = Pattern.compile("(\\[[^\\]]+\\])"); // Regex to find [TAG]
                    while ((line = reader.readLine()) != null) {
                        final String finalLine = line;
                        SwingUtilities.invokeLater(() -> {
                            try {
                                Matcher matcher = pattern.matcher(finalLine);
                                int lastIndex = 0;
                                while (matcher.find()) {
                                    // Append text before the tag
                                    if (lastIndex < matcher.start()) {
                                        doc.insertString(doc.getLength(), finalLine.substring(lastIndex, matcher.start()), defaultStyle);
                                    }

                                    // Append the tag with specific style
                                    String tag = matcher.group(1);
                                    doc.insertString(doc.getLength(), tag, getTagStyle(tag));
                                    lastIndex = matcher.end();
                                }
                                // Append any remaining text
                                if (lastIndex < finalLine.length()) {
                                    doc.insertString(doc.getLength(), finalLine.substring(lastIndex, finalLine.length()), defaultStyle);
                                }
                                doc.insertString(doc.getLength(), "\n", defaultStyle); // Add newline

                                consoleOutputArea.setCaretPosition(doc.getLength()); // Scroll to bottom
                            } catch (BadLocationException e) {
                                System.err.println("ERROR: Document insert error: " + e.getMessage());
                            }
                        });
                    }
                } catch (IOException e) {
                    System.err.println("ERROR: Console redirection thread error: " + e.getMessage());
                }
            }).start();
        } catch (IOException e) {
            System.err.println("ERROR: Failed to redirect console output: " + e.getMessage());
        }
    }

    private SimpleAttributeSet getTagStyle(String tag) {
        String content = tag.substring(1, tag.length() - 1); // Remove brackets

        if (content.contains("성공") || content.contains("텍스트추출완료") || content.contains("검증 완료")) {
            return greenStyle;
        } else if (content.contains("오류") || content.contains("실패") || content.contains("ERROR") || content.contains("FATAL ERROR")) {
            return redStyle;
        } else if (content.contains("경고") || content.contains("WARNING") || content.contains("덮어쓰기")) {
            return orangeStyle;
        } else if (content.contains("System 환경 정보") || content.contains("설정 정보") || content.contains("HttpServer") || content.contains("API 요청")) {
            return blueStyle;
        } else if (content.contains("IPLMS Hybrid Converter") || content.contains("가동 개시") || content.contains("탐색 완료")) {
            return purpleStyle;
        } else {
            return grayStyle;
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

        scheduler = Executors.newSingleThreadScheduledExecutor();
        int daemonIntervalMinutes = ConverterMain.getDaemonIntervalMinutes();
        System.out.println(">> [IPLMS Hybrid Converter] 데몬 모드로 시작합니다. 실행 주기: " + daemonIntervalMinutes + "분");

        scheduler.scheduleAtFixedRate(ConverterMain::runConversionCycle, 0, daemonIntervalMinutes, TimeUnit.MINUTES);
        
        // 내장 웹 서버 실행
        ConverterMain.startHttpServer();
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
            
            // 내장 웹 서버 종료
            ConverterMain.stopHttpServer();
            
            System.out.println(">> [IPLMS Hybrid Converter] 서비스가 종료되었습니다.");
        } else {
            System.out.println("서비스가 실행 중이 아닙니다.");
        }
        runButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void openOutputDirectory() {
        String outputDirStr = ConverterMain.getOutputDirSetting();
        if (outputDirStr == null || outputDirStr.trim().isEmpty()) {
            System.err.println("ERROR: 출력 폴더 설정이 비어 있습니다.");
            return;
        }

        File dir = new File(outputDirStr.trim());
        if (!dir.exists()) {
            System.out.println(">> [알림] 출력 폴더가 존재하지 않아 생성을 시도합니다: " + dir.getAbsolutePath());
            if (!dir.mkdirs()) {
                System.err.println("ERROR: 출력 폴더 생성에 실패했습니다: " + dir.getAbsolutePath());
                return;
            }
        }

        System.out.println(">> [시스템] 출력 폴더를 탐색기에서 엽니다: " + dir.getAbsolutePath());
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir);
            } else {
                new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
            }
        } catch (Exception ex) {
            System.err.println("ERROR: 출력 폴더를 여는 중 오류 발생: " + ex.getMessage());
            try {
                new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
            } catch (Exception ex2) {
                System.err.println("ERROR: 탐색기 실행 대체 시도 실패: " + ex2.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new ConverterGUI().setVisible(true);
        });
    }
}