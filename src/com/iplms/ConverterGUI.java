package com.iplms;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConverterGUI extends JFrame {
    private static final String LOG_DIR_NAME = "95_logs";
    private static final int MAX_DOCUMENT_LENGTH = 500_000;

    private JTextPane consoleOutputArea; // Changed from JTextArea to JTextPane
    private StyledDocument doc; // To manage styles in JTextPane
    private JButton runButton;
    private JButton stopButton;
    private JButton openOutputButton;
    private JButton clearLogButton;

    private ScheduledExecutorService scheduler;
    private String currentLogDate;

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
        clearLogButton.addActionListener(e -> clearConsoleDocument());

        stopButton.setEnabled(false);
    }
    /**
     * 표준 출력({@link System#out})과 표준 오류 출력({@link System#err})을 GUI 콘솔 영역으로 리다이렉트합니다.
     *
     * <p>{@link PipedOutputStream}과 {@link PipedInputStream}을 연결하여 콘솔 출력 내용을 별도 스레드에서
     * 한 줄씩 읽어 들인 뒤, 일별 로그 파일에 기록하고 Swing 이벤트 디스패치 스레드에서 화면에 표시합니다.</p>
     *
     * <p>로그 메시지에 포함된 {@code [태그]} 형식의 문자열은 태그 내용에 따라 서로 다른 스타일을 적용하여
     * {@link JTextPane}에 출력하며, 출력 후에는 자동으로 콘솔 하단으로 스크롤합니다.</p>
     *
     * <p>출력 스트림 설정, 콘솔 리다이렉션 또는 문서 삽입 중 오류가 발생하면 표준 오류 출력으로 오류 메시지를 기록합니다.</p>
     */
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

                        writeDailyLog(finalLine);

                        SwingUtilities.invokeLater(() -> {
                            try {
                                rotateDailyLogIfNeeded();

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
                                trimConsoleDocumentIfNeeded();
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
    /**
     * 전달받은 로그 메시지를 현재 날짜 기준의 일별 로그 파일에 기록합니다.
     *
     * <p>로그 파일은 설정된 로그 디렉터리 하위에 {@code yyyyMMdd.log} 형식의 파일명으로 생성되며,
     * 각 로그 라인은 {@code yyyy-MM-dd HH:mm:ss} 형식의 기록 시각과 함께 UTF-8 인코딩으로 추가됩니다.</p>
     *
     * <p>로그 디렉터리가 존재하지 않을 경우 생성을 시도하며, 디렉터리 생성 또는 파일 기록 중 오류가 발생하면
     * 별도의 예외 전파 없이 로그 기록을 중단합니다.</p>
     *
     * @param line 로그 파일에 기록할 메시지 한 줄
     */
    private void writeDailyLog(String line) {
        String logDate = new SimpleDateFormat("yyyyMMdd").format(new Date());
        File logDir = new File( ConverterMain.getLogDirSetting());

        if (!logDir.exists() && !logDir.mkdirs()) {
            return;
        }

        File logFile = new File(logDir, logDate + ".log");

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
            writer.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.write(" ");
            writer.write(line);
            writer.newLine();
        } catch (IOException ignored) {
        }
    }

    private void rotateDailyLogIfNeeded() {
        String today = new SimpleDateFormat("yyyyMMdd").format(new Date());

        if (currentLogDate == null) {
            currentLogDate = today;
            return;
        }

        if (!currentLogDate.equals(today)) {
            clearConsoleDocument();
            currentLogDate = today;
            System.gc();
        }
    }

    private void clearConsoleDocument() {
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException e) {
            System.err.println("ERROR: Failed to clear log: " + e.getMessage());
        }
    }

    private void trimConsoleDocumentIfNeeded() throws BadLocationException {
        if (doc.getLength() <= MAX_DOCUMENT_LENGTH) {
            return;
        }

        int removeLength = doc.getLength() - (MAX_DOCUMENT_LENGTH / 2);
        doc.remove(0, removeLength);
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