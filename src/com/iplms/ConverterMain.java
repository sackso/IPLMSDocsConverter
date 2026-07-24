package com.iplms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

public class ConverterMain {

    private static String libreOfficePath;
    private static String autoCadPath;
    private static String autoCadScriptPath;
    private static int autoCadTimeoutSeconds;
    private static String inputDirSetting;
    private static String outputDirSetting;
    private static int timeoutSeconds;
    private static String reportExcelName;
    private static int daemonIntervalMinutes;
    private static int serverPort;
    private static HttpServer httpServer;
    private static final ReentrantLock conversionLock = new ReentrantLock();

    private static final ConcurrentLinkedQueue<ReportRow> reportQueue = new ConcurrentLinkedQueue<>();
    private static final long MEMORY_LIMIT_BYTES = 2L * 1024 * 1024 * 1024; // 2GB

    // Removed main method, as GUI will be the entry point

    public static void runConversionCycle() { // Made public for GUI to access
        try {
            String timestamp = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
            System.out.println("\n\n=====================================================");
            System.out.println(">> [" + new java.util.Date() + "] 정기 변환 작업을 시작합니다. (ID: " + timestamp + ")");
            System.out.println("=====================================================");

            checkMemoryAndExitIfNeeded();

            if (inputDirSetting == null || inputDirSetting.trim().isEmpty()) {
                System.err.println("ERROR: config.properties 파일에 converter.input.dir 설정이 누락되었거나 비어있습니다.");
                return;
            }

            File inputDir = new File(inputDirSetting.trim());
            if (!inputDir.exists() || !inputDir.isDirectory()) {
                System.err.println("ERROR: 설정된 입력 폴더가 존재하지 않거나 디렉토리가 아닙니다 -> " + inputDir.getAbsolutePath());
                return;
            }

            File baseOutputDir = new File(outputDirSetting.isEmpty() ? inputDir.getAbsolutePath() : outputDirSetting.trim());
            File timestampedOutputDir = new File(baseOutputDir, timestamp);
            if (!timestampedOutputDir.exists()) {
                timestampedOutputDir.mkdirs();
            }

            System.out.println(">> [IPLMS Hybrid Converter] 폴더 탐색 및 안전 순차 변환 가동 개시");
            System.out.println(">> 탐색 대상 입력 폴더: " + inputDir.getAbsolutePath());
            System.out.println(">> 이번 주기 출력 폴더: " + timestampedOutputDir.getAbsolutePath());
            System.out.println(">> LibreOffice 경로: " + libreOfficePath);

            List<File> targetFiles = new ArrayList<>();
            scanDirectory(inputDir, targetFiles);

            if (targetFiles.isEmpty()) {
                System.out.println(">> [알림] 입력 폴더 이하에서 변환 가능한 대상 문서를 찾지 못했습니다.");
                return;
            }

            System.out.println(">> [탐색 완료] 총 " + targetFiles.size() + "개의 대상 문서가 수집되었습니다. 순차 엔진을 기동합니다.\n\n");

            ExecutorService conversionExecutor = Executors.newSingleThreadExecutor();
            reportQueue.clear();
            List<String> resultFilePaths = new CopyOnWriteArrayList<>(); // Thread-safe list for result paths

            for (File srcFile : targetFiles) {
                Callable<Boolean> conversionTask = () -> {
                    String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
                    File destPdf = new File(timestampedOutputDir, baseName + ".pdf");
                    File destTxt = new File(timestampedOutputDir, baseName + ".txt");

                    resultFilePaths.add(destPdf.getAbsolutePath());
                    resultFilePaths.add(destTxt.getAbsolutePath());

                    if (destPdf.exists()) {
                        System.out.println(">> [덮어쓰기] 기존 PDF 파일 제거 및 갱신: " + destPdf.getName());
                        destPdf.delete();
                    }
                    if (destTxt.exists()) {
                        destTxt.delete();
                    }

                    String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();
                    String fileVersion = detectFileVersion(srcFile, ext);

                    double fileSizeKb = srcFile.length() / 1024.0;
                    String formattedSize = String.format("%.2f", fileSizeKb);

                    ReportRow rowData = new ReportRow();
                    rowData.filePath = srcFile.getAbsolutePath();
                    rowData.fileName = srcFile.getName();
                    rowData.fileType = ext.toUpperCase() + " (" + fileVersion + ")";
                    rowData.fileSize = formattedSize;

                    long startTime = System.nanoTime();

                    try {
                        boolean isConverted = convertToPdf(srcFile, destPdf, fileVersion);
                        if (isConverted && destPdf.exists()) {
                            rowData.pdfResult = "성공";
                            boolean isExtracted = extractTextFromPdf(destPdf, destTxt);
                            rowData.txtResult = isExtracted ? "성공" : "실패";
                        } else {
                            rowData.pdfResult = "실패";
                            rowData.txtResult = "실패 (PDF 변환 실패됨)";
                        }
                    } catch (Exception e) {
                        System.err.println("ERROR: [런타임 에러]: " + srcFile.getName());
                        File errFile = writeErrorFile(srcFile, e.getMessage(), timestampedOutputDir);
                        if (errFile != null) resultFilePaths.add(errFile.getAbsolutePath());
                        rowData.pdfResult = "실패 (에러)";
                        rowData.txtResult = "실패";
                    }
                    long endTime = System.nanoTime();
                    double elapsedTimeSeconds = (endTime - startTime) / 1_000_000_000.0;
                    rowData.elapsedTime = String.format("%.2f", elapsedTimeSeconds);

                    System.out.println(">> [변환 종료] 파일명: " + srcFile.getName()
                            + " | 용량: " + rowData.fileSize + " KB"
                            + " | 소요시간: " + rowData.elapsedTime + "초");

                    reportQueue.add(rowData);
                    return true;
                };

                Future<Boolean> future = conversionExecutor.submit(conversionTask);

                try {
                    future.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    System.err.println("ERROR: [타임아웃] 변환 시간 초과 (" + timeoutSeconds + "초 제한): " + srcFile.getName());
                    future.cancel(true);

                    ReportRow timeoutRow = new ReportRow();
                    timeoutRow.filePath = srcFile.getAbsolutePath();
                    timeoutRow.fileName = srcFile.getName();
                    timeoutRow.fileType = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toUpperCase();
                    timeoutRow.fileSize = String.format("%.2f", srcFile.length() / 1024.0);
                    timeoutRow.pdfResult = "실패 (타임아웃)";
                    timeoutRow.txtResult = "실패";
                    timeoutRow.elapsedTime = String.valueOf(timeoutSeconds) + ".00";

                    reportQueue.add(timeoutRow);
                    File errFile = writeErrorFile(srcFile, "제한시간 " + timeoutSeconds + "초 초과로 인한 강제 중단", timestampedOutputDir);
                    if (errFile != null) resultFilePaths.add(errFile.getAbsolutePath());
                } catch (Exception e) {
                    System.err.println("WARNING: [경고] 내부 스레드 제어 오류 패스: " + srcFile.getName() + " -> " + e.getMessage());
                }
            }

            conversionExecutor.shutdown();
            try {
                if (!conversionExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
                    conversionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                conversionExecutor.shutdownNow();
            }

            if (!reportQueue.isEmpty()) {
                File csvFile = generateCsvReport(timestampedOutputDir);
                if (csvFile != null) resultFilePaths.add(csvFile.getAbsolutePath());
            }

            writeResultFileList(baseOutputDir, timestamp, resultFilePaths);

            System.out.println(">> [IPLMS Hybrid Converter] 모든 디렉토리 대기열 처리 및 리포트 저장 완료");

        } catch (Exception e) {
            System.err.println("ERROR: 주기 작업 실행 중 예상치 못한 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void checkMemoryAndExitIfNeeded() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapMemoryUsage = memoryBean.getHeapMemoryUsage();
        long usedMemory = heapMemoryUsage.getUsed();

        System.out.printf(">> [메모리 확인] 현재 사용량: %.2f MB%n", usedMemory / (1024.0 * 1024.0));

        if (usedMemory > MEMORY_LIMIT_BYTES) {
            System.err.println("WARNING: [메모리 경고] 사용량이 임계값(2GB)을 초과했습니다. 강제 GC를 실행합니다.");
            System.gc();

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            heapMemoryUsage = memoryBean.getHeapMemoryUsage();
            usedMemory = heapMemoryUsage.getUsed();
            System.out.printf(">> [메모리 재확인] GC 후 사용량: %.2f MB%n", usedMemory / (1024.0 * 1024.0));

            if (usedMemory > MEMORY_LIMIT_BYTES) {
                String errorMessage = "메모리 확보 실패. GC 실행 후에도 사용량이 2GB를 초과하여 시스템을 강제 종료합니다.";
                System.err.println("FATAL ERROR: " + errorMessage);
                writeSystemErrorFile(errorMessage);
                System.exit(1);
            }
        }
    }

    private static void scanDirectory(File dir, List<File> resultList) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, resultList);
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".docx") || name.endsWith(".doc") ||
                        name.endsWith(".xlsx") || name.endsWith(".xls") ||
                        name.endsWith(".pptx") || name.endsWith(".ppt") ||
                        name.endsWith(".hwpx") || name.endsWith(".hwp") ||
                        name.endsWith(".dwg")) {
                    resultList.add(file);
                }
            }
        }
    }

    private static boolean convertToPdf(File srcFile, File destPdf, String fileVersion) throws Exception {
        String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();
        System.out.println(">> [변환 시작] 포맷: [" + ext.toUpperCase() + "] | 문서 버전: [" + fileVersion + "] | 파일명: " + srcFile.getName());

        if ("dwg".equals(ext)) {
            return runAutoCadConverter(srcFile, destPdf);
        } else {
            conversionLock.lock();
            try {
                return runLibreOfficeConverter(srcFile, destPdf);
            } finally {
                conversionLock.unlock();
            }
        }
    }

    private static boolean runLibreOfficeConverter(File srcFile, File destPdf) throws Exception {
        String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();
        File outputDir = destPdf.getParentFile();
        ProcessBuilder pb;

        if ("hwp".equals(ext) || "hwpx".equals(ext)) {
            pb = new ProcessBuilder(
                    libreOfficePath,
                    "--headless",
                    "--infilter=Hwp2002_File",
                    "--convert-to", "pdf:writer_pdf_Export",
                    "--outdir", outputDir.getAbsolutePath(),
                    srcFile.getAbsolutePath()
            );
        } else {
            pb = new ProcessBuilder(
                    libreOfficePath,
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", outputDir.getAbsolutePath(),
                    srcFile.getAbsolutePath()
            );
        }

        Process process = pb.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new TimeoutException("LibreOffice 프로세스가 " + timeoutSeconds + "초 내에 완료되지 않았습니다.");
        }

        int exitCode = process.exitValue();

        if (exitCode == 0) {
            String defaultGeneratedName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.')) + ".pdf";
            File generatedPdf = new File(outputDir, defaultGeneratedName);

            if (generatedPdf.exists()) {
                if (!generatedPdf.getAbsolutePath().equals(destPdf.getAbsolutePath())) {
                    if (destPdf.exists()) destPdf.delete();
                    generatedPdf.renameTo(destPdf);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean runAutoCadConverter(File srcFile, File destPdf) throws Exception {
        if (autoCadPath == null || autoCadPath.trim().isEmpty()) {
            throw new FileNotFoundException("AutoCAD 실행 파일 경로(converter.autocad.path)가 설정되지 않았습니다.");
        }
        File autoCadExec = new File(autoCadPath.trim());
        if (!autoCadExec.exists()) {
            throw new FileNotFoundException("AutoCAD 실행 파일을 찾을 수 없습니다 -> " + autoCadPath);
        }
        if (autoCadScriptPath == null || autoCadScriptPath.trim().isEmpty()) {
            throw new FileNotFoundException("AutoCAD 변환 스크립트 경로(converter.autocad.script.path)가 설정되지 않았습니다.");
        }
        File scriptFile = new File(autoCadScriptPath.trim());
        if (!scriptFile.exists()) {
            throw new FileNotFoundException("AutoCAD 변환 스크립트 파일을 찾을 수 없습니다 -> " + autoCadScriptPath);
        }

        File outputDir = destPdf.getParentFile();
        ProcessBuilder pb = new ProcessBuilder(
                autoCadPath.trim(),
                "/i", srcFile.getAbsolutePath(),
                "/s", scriptFile.getAbsolutePath(),
                "/l", "en-US"
        );
        pb.directory(outputDir);

        Process process = pb.start();
        if (!process.waitFor(autoCadTimeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new TimeoutException("AutoCAD AcCoreConsole 프로세스가 " + autoCadTimeoutSeconds + "초 내에 완료되지 않았습니다.");
        }

        int exitCode = process.exitValue();
        if (exitCode == 0) {
            String defaultGeneratedName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.')) + ".pdf";
            File generatedPdf = new File(outputDir, defaultGeneratedName);
            if (!generatedPdf.exists()) {
                generatedPdf = new File(srcFile.getParentFile(), defaultGeneratedName);
            }

            if (generatedPdf.exists()) {
                if (!generatedPdf.getAbsolutePath().equals(destPdf.getAbsolutePath())) {
                    if (destPdf.exists()) destPdf.delete();
                    generatedPdf.renameTo(destPdf);
                }
                return true;
            }
        }
        return false;
    }

    private static String detectFileVersion(File file, String ext) {
        // ... (내용 동일)
        return "표준 규격";
    }

    private static String parseTagValueFromStream(InputStream is, String key, String defaultPrefix) throws IOException {
        // ... (내용 동일)
        return defaultPrefix;
    }

    private static boolean extractTextFromPdf(File pdfFile, File destTxt) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(destTxt), StandardCharsets.UTF_8))) {
                writer.write(text);
            }
            System.out.println(">> [텍스트 추출 완료]: " + destTxt.getName());
            return true;
        } catch (Exception e) {
            System.err.println("WARNING: [텍스트 추출 실패 - 패스]: " + pdfFile.getName() + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private static File generateCsvReport(File exportFolder) {
        File csvFile = new File(exportFolder, reportExcelName);
        System.out.println(">> [CSV 내보내기 개시] 최종 리포트를 작성합니다 -> " + csvFile.getAbsolutePath());

        int index = 1;

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(csvFile), StandardCharsets.UTF_8))) {
            pw.write("\uFEFF"); // BOM for Excel
            pw.println("번호,파일경로,파일명,파일종류,pdf변환결과(성공/실패),텍스트추출결과,파일용량(KB),소요시간(초)");

            for (ReportRow row : reportQueue) {
                pw.print(index++ + ",");
                pw.print(escapeCsv(row.filePath) + ",");
                pw.print(escapeCsv(row.fileName) + ",");
                pw.print(escapeCsv(row.fileType) + ",");
                pw.print(escapeCsv(row.pdfResult) + ",");
                pw.print(escapeCsv(row.txtResult) + ",");
                pw.print(escapeCsv(row.fileSize) + ",");
                pw.println(escapeCsv(row.elapsedTime));
            }
            System.out.println(">> [CSV 리포트 생성 완료] 총 " + (index - 1) + "건의 변환 이력 저장 완료.");
            return csvFile;
        } catch (Exception e) {
            System.err.println("ERROR: CSV 보고서 생성 도중 에러가 발생했습니다: " + e.getMessage());
            return null;
        }
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static File writeErrorFile(File srcFile, String errMsg, File targetDir) {
        String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
        File errFile = new File(targetDir, baseName + "_ERR.txt");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(errFile), StandardCharsets.UTF_8))) {
            pw.println("=====================================================");
            pw.println("ERROR: IPLMS 변환 오류 리포트");
            pw.println("=====================================================");
            pw.println("대상 원본 파일: " + srcFile.getAbsolutePath());
            pw.println("발생 시각: " + new java.util.Date());
            pw.println("오류 세부 명세: " + errMsg);
            return errFile;
        } catch (Exception e) {
            System.err.println("ERROR: 에러 로그 파일 쓰기 실패: " + e.getMessage());
            return null;
        }
    }

    private static void writeSystemErrorFile(String errMsg) {
        File baseDir = new File(System.getProperty("user.dir"));
        File errFile = new File(baseDir, "SYSTEM_FATAL_ERROR.txt");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(errFile), StandardCharsets.UTF_8))) {
            pw.println("=====================================================");
            pw.println("FATAL ERROR: IPLMS 시스템 치명적 오류 리포트");
            pw.println("=====================================================");
            pw.println("발생 시각: " + new java.util.Date());
            pw.println("오류 세부 명세: " + errMsg);
        } catch (Exception e) {
            System.err.println("ERROR: 시스템 에러 로그 파일 쓰기 실패: " + e.getMessage());
        }
    }

    private static void writeResultFileList(File baseOutputDir, String timestamp, List<String> filePaths) {
        File resultListFile = new File(baseOutputDir, timestamp + "_result.txt");
        System.out.println(">> [결과 목록 생성] 이번 주기의 모든 결과 파일 경로를 저장합니다 -> " + resultListFile.getAbsolutePath());
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(resultListFile), StandardCharsets.UTF_8))) {
            for (String path : filePaths) {
                pw.println(path);
            }
            System.out.println(">> [결과 목록 생성 완료]");
        } catch (Exception e) {
            System.err.println("ERROR: 결과 목록 파일 생성 중 에러가 발생했습니다: " + e.getMessage());
        }
    }

    public static void loadProperties() { // Made public for GUI to access
        Properties prop = new Properties();
        boolean loaded = false;

        // 1. 실행 중인 JAR 또는 클래스 파일의 상위 폴더(baseDir)에서 config.properties 탐색 (외장 설정 지원)
        try {
            String codePath = ConverterMain.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            if (codePath != null) {
                File baseDir = new File(codePath).getParentFile();
                if (baseDir != null) {
                    File propFile = new File(baseDir, "config.properties");
                    if (propFile.exists()) {
                        try (InputStream is = new FileInputStream(propFile)) {
                            prop.load(is);
                            // System.out.println(">> [설정 로드 성공] 실행 파일 상위 폴더 경로: " + propFile.getAbsolutePath()); // Removed
                            loaded = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 예외 발생 시 무시하고 다음 단계로 진행
        }

        // 2. 현재 작업 디렉토리(Working Directory)에서 config.properties 탐색
        if (!loaded) {
            File propFile = new File("config.properties");
            if (propFile.exists()) {
                try (InputStream is = new FileInputStream(propFile)) {
                    prop.load(is);
                    // System.out.println(">> [설정 로드 성공] 작업 디렉토리(Working Directory) 경로: " + propFile.getAbsolutePath()); // Removed
                    loaded = true;
                } catch (IOException e) {
                    System.err.println("WARNING: config.properties 외부 파일을 찾았으나 읽기에 실패했습니다: " + e.getMessage());
                }
            }
        }

        // 3. JAR 내부 혹은 클래스패스(Classpath) 루트에서 config.properties 탐색 (내장 설정 및 IDE 디버그용)
        if (!loaded) {
            try (InputStream is = ConverterMain.class.getResourceAsStream("/config.properties")) {
                if (is != null) {
                    prop.load(is);
                    // System.out.println(">> [설정 로드 성공] 클래스패스(JAR 내부 리소스 / IDE 빌드 출력)에서 로드 완료"); // Removed
                    loaded = true;
                }
            } catch (IOException e) {
                System.err.println("WARNING: 클래스패스 내부의 config.properties 로드 중 에러가 발생했습니다: " + e.getMessage());
            }
        }

        if (!loaded) {
            System.err.println("WARNING: config.properties 설정을 찾을 수 없습니다. 기본값을 사용합니다.");
        }

        libreOfficePath = prop.getProperty("converter.libreoffice.path", "C:\\Program Files\\LibreOffice\\program\\soffice.exe");
        autoCadPath = prop.getProperty("converter.autocad.path", "C:\\Program Files\\Autodesk\\AutoCAD 2024\\accoreconsole.exe");
        autoCadScriptPath = prop.getProperty("converter.autocad.script.path", "C:\\IPLMS\\scripts\\dwg2pdf.scr");
        autoCadTimeoutSeconds = Integer.parseInt(prop.getProperty("converter.autocad.timeout.seconds", "120"));
        inputDirSetting = prop.getProperty("converter.input.dir", "");
        outputDirSetting = prop.getProperty("converter.output.dir", "");
        timeoutSeconds = Integer.parseInt(prop.getProperty("converter.timeout.seconds", "90"));
        reportExcelName = prop.getProperty("converter.report.excel.name", "conversion_report.csv");
        daemonIntervalMinutes = Integer.parseInt(prop.getProperty("daemon.interval.minutes", "10"));
        serverPort = Integer.parseInt(prop.getProperty("converter.server.port", "9119")); // Load server port
    }

    public static int getDaemonIntervalMinutes() { // Added getter for GUI to retrieve interval
        return daemonIntervalMinutes;
    }

    public static String getOutputDirSetting() {
        if (outputDirSetting == null || outputDirSetting.trim().isEmpty()) {
            return inputDirSetting != null ? inputDirSetting.trim() : "";
        }
        return outputDirSetting.trim();
    }

    // Add startHttpServer method
    public static void startHttpServer() {
        if (httpServer != null) {
            System.out.println(">> [HttpServer] 서버가 이미 실행 중입니다.");
            return;
        }
        try {
            httpServer = HttpServer.create(new InetSocketAddress(serverPort), 0);
            httpServer.createContext("/api/convert", new ConvertHandler());
            httpServer.setExecutor(Executors.newCachedThreadPool()); // Use a cached thread pool
            httpServer.start();
            System.out.println(">> [HttpServer] 내장 웹 서버가 포트 " + serverPort + "에서 실행 중입니다.");
        } catch (Exception e) {
            System.err.println("ERROR: [HttpServer] 서버 시작 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Add stopHttpServer method
    public static void stopHttpServer() {
        if (httpServer != null) {
            System.out.println(">> [HttpServer] 서버 종료 중...");
            httpServer.stop(2); // Stop with a 2-second delay
            httpServer = null;
            System.out.println(">> [HttpServer] 서버가 성공적으로 종료되었습니다.");
        }
    }

    // Add ConvertHandler inner class
    private static class ConvertHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*"); // CORS
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1); // No content for OPTIONS
                return;
            }

            String filePath = null;

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getRawQuery();
                Map<String, String> params = parseQueryParams(query);
                filePath = params.get("filePath");
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    body.append(line);
                }
                
                if (contentType != null && contentType.contains("application/json")) {
                    filePath = parseJsonFilePath(body.toString());
                } else { // Assume form-urlencoded if not JSON
                    Map<String, String> params = parseQueryParams(body.toString());
                    filePath = params.get("filePath");
                }
            } else {
                sendResponse(exchange, 405, "{\"status\":\"error\", \"message\":\"Method Not Allowed. Use GET or POST.\"}");
                return;
            }

            if (filePath == null || filePath.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"Missing 'filePath' parameter.\"}");
                return;
            }

            File srcFile = new File(filePath.trim());
            if (!srcFile.exists() || !srcFile.isFile()) {
                sendResponse(exchange, 404, "{\"status\":\"error\", \"message\":\"File not found or is not a valid file -> " + srcFile.getAbsolutePath() + "\"}");
                return;
            }

            String name = srcFile.getName().toLowerCase();
            if (!(name.endsWith(".docx") || name.endsWith(".doc") ||
                  name.endsWith(".xlsx") || name.endsWith(".xls") ||
                  name.endsWith(".pptx") || name.endsWith(".ppt") ||
                  name.endsWith(".hwpx") || name.endsWith(".hwp") ||
                  name.endsWith(".dwg"))) {
                sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"Unsupported file format. Supported: docx, doc, xlsx, xls, pptx, ppt, hwpx, hwp, dwg\"}");
                return;
            }

            System.out.println(">> [API 요청] 실시간 변환 요청 접수: " + srcFile.getAbsolutePath());

            try {
                String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
                File parentDir = srcFile.getParentFile();
                File destPdf = new File(parentDir, baseName + ".pdf");
                File destTxt = new File(parentDir, baseName + ".txt");

                if (destPdf.exists()) {
                    destPdf.delete();
                }
                if (destTxt.exists()) {
                    destTxt.delete();
                }

                String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();
                String fileVersion = detectFileVersion(srcFile, ext);

                long startTime = System.nanoTime();
                boolean isConverted = convertToPdf(srcFile, destPdf, fileVersion);
                long endTime = System.nanoTime();
                double elapsedTimeSeconds = (endTime - startTime) / 1_000_000_000.0;

                if (isConverted && destPdf.exists()) {
                    boolean isTxtExtracted = extractTextFromPdf(destPdf, destTxt);
                    
                    String jsonResponse = String.format(
                        "{\"status\":\"success\", \"pdfPath\":\"%s\", \"txtPath\":\"%s\", \"txtExtracted\":%b, \"elapsedTime\":\"%.2f초\"}",
                        escapeJson(destPdf.getAbsolutePath()),
                        escapeJson(destTxt.getAbsolutePath()),
                        isTxtExtracted,
                        elapsedTimeSeconds
                    );
                    sendResponse(exchange, 200, jsonResponse);
                } else {
                    sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"Conversion failed.\"}");
                }
            } catch (Exception e) {
                System.err.println("ERROR: [API 변환 에러] " + srcFile.getName() + " -> " + e.getMessage());
                String jsonResponse = String.format("{\"status\":\"error\", \"message\":\"%s\"}", escapeJson(e.getMessage()));
                sendResponse(exchange, 500, jsonResponse);
            }
        }

        private Map<String, String> parseQueryParams(String query) {
            Map<String, String> result = new HashMap<>();
            if (query == null || query.isEmpty()) return result;
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                try {
                    String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) : pair;
                    String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()) : "";
                    result.put(key, value);
                } catch (Exception e) {
                    // ignore
                }
            }
            return result;
        }

        private String parseJsonFilePath(String json) {
            // Simple JSON parsing for filePath. Assumes "filePath": "value"
            if (json == null || json.isEmpty()) return null;
            int keyIdx = json.indexOf("\"filePath\"");
            if (keyIdx == -1) return null;
            int colonIdx = json.indexOf(":", keyIdx);
            if (colonIdx == -1) return null;
            int startQuote = json.indexOf("\"", colonIdx);
            if (startQuote == -1) return null;
            int endQuote = json.indexOf("\"", startQuote + 1);
            if (endQuote == -1) return null;
            String escapedPath = json.substring(startQuote + 1, endQuote);
            return escapedPath.replace("\\\\", "\\").replace("\\\"", "\""); // Unescape JSON string
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
            byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String escapeJson(String value) {
            if (value == null) return "";
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    private static class ReportRow {
        String filePath;
        String fileName;
        String fileType;
        String pdfResult;
        String txtResult;
        String elapsedTime;
        String fileSize;
    }
}