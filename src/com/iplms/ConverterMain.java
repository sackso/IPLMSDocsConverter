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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

public class ConverterMain {

    private static String libreOfficePath;
    private static String libreOfficeProfilePath;
    private static String autoCadPath;
    private static int autoCadTimeoutSeconds;
    private static String inputDirSetting;
    private static String outputDirSetting;
    private static String logDirSetting;
    private static int guiLogMaxLength;
    private static int timeoutSeconds;
    private static String reportExcelName;
    private static int daemonIntervalMinutes;
    private static int serverPort;
    private static long memoryLimitBytes;
    private static HttpServer httpServer;
    private static final ReentrantLock conversionLock = new ReentrantLock();

    private static final ConcurrentLinkedQueue<ReportRow> reportQueue = new ConcurrentLinkedQueue<>();

    public static void runConversionCycle() {

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
            if (!baseOutputDir.exists()) {
                baseOutputDir.mkdirs();
            }

            System.out.println(">> [IPLMS Hybrid Converter] 폴더 탐색 및 안전 순차 변환 가동 개시");
            System.out.println(">> 탐색 대상 입력 폴더: " + inputDir.getAbsolutePath());
            System.out.println(">> 출력 폴더: " + baseOutputDir.getAbsolutePath());
            System.out.println(">> LibreOffice 경로: " + libreOfficePath);
            System.out.println(">> logs 경로: " + logDirSetting);

            List<File> targetFiles = new ArrayList<>();
            scanDirectory(inputDir, targetFiles);

            if (targetFiles.isEmpty()) {
                System.out.println(">> [알림] 입력 폴더 이하에서 변환 가능한 대상 문서를 찾지 못했습니다.");
                return;
            }

            System.out.println(">> [탐색 완료] 총 " + targetFiles.size() + "개의 대상 문서가 수집되었습니다. 순차 엔진을 기동합니다.\n\n");

            ExecutorService conversionExecutor = Executors.newSingleThreadExecutor();
            reportQueue.clear();
            List<String> resultFilePaths = new CopyOnWriteArrayList<>();

            for (File srcFile : targetFiles) {
                Callable<Boolean> conversionTask = () -> {
                    String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
                    File relativeOutputDir = resolveRelativeOutputDir(inputDir, baseOutputDir, srcFile.getParentFile());
                    if (!relativeOutputDir.exists() && !relativeOutputDir.mkdirs()) {
                        throw new IOException("출력 하위 폴더 생성 실패: " + relativeOutputDir.getAbsolutePath());
                    }

                    File destPdf = new File(relativeOutputDir, baseName + ".pdf");
                    File destTxt = new File(relativeOutputDir, baseName + ".txt");

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

                            // 변환 성공 후 원본 파일(DWG 포함)을 Output 폴더로 이동
                            moveSourceFileToOutputDir(srcFile, relativeOutputDir);
                        } else {
                            rowData.pdfResult = "실패";
                            rowData.txtResult = "실패 (PDF 변환 실패됨)";
                        }
                    } catch (Exception e) {
                        System.err.println("ERROR: [런타임 에러]: " + srcFile.getName());
                        File errFile = writeErrorFile(srcFile, e.getMessage(), relativeOutputDir);
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

                // ConverterMain.java - runConversionCycle() 내 future.get() 호출부 수정

                String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();

                int activeTimeout = "dwg".equals(ext) ? autoCadTimeoutSeconds : timeoutSeconds;

                try {
                    // 기존: future.get(timeoutSeconds, TimeUnit.SECONDS);
                    future.get(activeTimeout, TimeUnit.SECONDS); // [수정] DWG는 autoCadTimeoutSeconds(720초) 적용
                } catch (TimeoutException e) {
                    System.err.println("ERROR: [타임아웃] 변환 시간 초과 (" + activeTimeout + "초 제한): " + srcFile.getName());
                    future.cancel(true);

                    ReportRow timeoutRow = new ReportRow();
                    timeoutRow.filePath = srcFile.getAbsolutePath();
                    timeoutRow.fileName = srcFile.getName();
                    timeoutRow.fileType = ext.toUpperCase();
                    timeoutRow.fileSize = String.format("%.2f", srcFile.length() / 1024.0);
                    timeoutRow.pdfResult = "실패 (타임아웃)";
                    timeoutRow.txtResult = "실패";
                    timeoutRow.elapsedTime = String.valueOf(activeTimeout) + ".00";

                    reportQueue.add(timeoutRow);
                    File relativeOutputDir = resolveRelativeOutputDir(inputDir, baseOutputDir, srcFile.getParentFile());
                    File errFile = writeErrorFile(srcFile, "제한시간 " + activeTimeout + "초 초과로 인한 강제 중단", relativeOutputDir);
                    if (errFile != null) resultFilePaths.add(errFile.getAbsolutePath());
                }catch (Exception e) {
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
                File csvFile = generateCsvReport(baseOutputDir);
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

        if (usedMemory > memoryLimitBytes) {
            System.err.println("WARNING: [메모리 경고] 사용량이 임계값(" + formatBytesToMb(memoryLimitBytes) + " MB)을 초과했습니다. 강제 GC를 실행합니다.");
            System.gc();

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            heapMemoryUsage = memoryBean.getHeapMemoryUsage();
            usedMemory = heapMemoryUsage.getUsed();
            System.out.printf(">> [메모리 재확인] GC 후 사용량: %.2f MB%n", usedMemory / (1024.0 * 1024.0));

            if (usedMemory > memoryLimitBytes) {
                String errorMessage = "메모리 확보 실패. GC 실행 후에도 사용량이 " + formatBytesToMb(memoryLimitBytes) + " MB를 초과하여 시스템을 강제 종료합니다.";
                System.err.println("FATAL ERROR: " + errorMessage);
                writeSystemErrorFile(errorMessage);
                System.exit(1);
            }
        }
    }

    private static String formatBytesToMb(long bytes) {
        return String.format("%.2f", bytes / (1024.0 * 1024.0));
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
        List<String> command = new ArrayList<>();

        command.add(libreOfficePath);
        command.add("--headless");
        command.add("--norestore");

        File baseDir = new File(libreOfficeProfilePath);
        File userProfileDir = new File(baseDir, "libreoffice_profile");

        switch (ext) {
            case "hwp":
            case "hwpx":
                command.add("--infilter=Hwp2002_File");
                command.add("--convert-to");
                command.add("pdf:writer_pdf_Export");
                break;
            case "docx":
            case "doc":
                command.add("-env:UserInstallation=file:///" + userProfileDir.getAbsolutePath().replace("\\", "/"));
                command.add("--convert-to");
                command.add("pdf:writer_pdf_Export");
                break;
            case "xlsx":
            case "xls":
                command.add("-env:UserInstallation=file:///" + userProfileDir.getAbsolutePath().replace("\\", "/"));
                command.add("--convert-to");
                command.add("pdf:calc_pdf_Export");
                break;
            case "pptx":
            case "ppt":
                command.add("-env:UserInstallation=file:///" + userProfileDir.getAbsolutePath().replace("\\", "/"));
                command.add("--convert-to");
                command.add("pdf:impress_pdf_Export:{\"PDFBugExport\":{\"type\":\"boolean\",\"value\":\"false\"},\"ExportFormFields\":{\"type\":\"boolean\",\"value\":\"true\"}}");
                break;
            default:
                command.add("-env:UserInstallation=file:///" + userProfileDir.getAbsolutePath().replace("\\", "/"));
                command.add("--convert-to");
                command.add("pdf");
                break;
        }

        command.add("--outdir");
        command.add(outputDir.getAbsolutePath());
        command.add(srcFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);

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

    /**
     * AutoCAD 2027 AcCoreConsole(Headless 엔진)을 호출하여 DWG를 PDF로 변환 후 프로세스를 종료합니다.
     * cmd 예제)C:\Program Files\Autodesk\AutoCAD 2027>"C:\Program Files\Autodesk\AutoCAD 2027\accoreconsole.exe" /i "c:\IPLMS\91_input\bottom_plate.dwg" /s "c:\IPLMS\92_output\accore_6784213119159777660.scr" /l "en-US" > "c:\IPLMS\92_output\console_debug.log"
     * 한글/공백/특수문자 경로 오작동 방지를 위해 타임스탬프 기반 임시 영문 치환 파이프라인 적용.
     */
    private static boolean runAutoCadConverter(File srcFile, File destPdf) throws Exception {
        if (autoCadPath == null || autoCadPath.trim().isEmpty()) {
            throw new FileNotFoundException("AutoCAD AcCoreConsole 경로(converter.autocad.path)가 설정되지 않았습니다.");
        }
        File autoCadExec = new File(autoCadPath.trim());
        if (!autoCadExec.exists()) {
            throw new FileNotFoundException("AcCoreConsole 실행 파일을 찾을 수 없습니다 -> " + autoCadPath);
        }

        File outputDir = destPdf.getParentFile();
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("출력 디렉토리 생성 실패: " + outputDir.getAbsolutePath());
        }

        // =========================================================================
        // [개선사항 1 & 2] 원본 정보 보존 및 타임스탬프 기반 임시 영문 파일 정의
        // =========================================================================
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        File tempSrcDwg = new File(srcFile.getParentFile(), "temp_dwg_" + timestamp + ".dwg");
        File tempDestPdf = new File(outputDir, "temp_pdf_" + timestamp + ".pdf");
        File tempScript = null;

        try {
            // 원본 DWG -> 임시 영문 DWG 파일로 복사
            Files.copy(srcFile.toPath(), tempSrcDwg.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // =========================================================================
            // [개선사항 3] temp 형태로 변경된 파일 경로로 .scr 스크립트 작성 및 PDF 변환
            // =========================================================================
            tempScript = File.createTempFile("accore_", ".scr", outputDir);

            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(tempScript), StandardCharsets.UTF_8))) {
                // 시스템 변수 및 속도 최적화 가드
                pw.println("EXPERT"); pw.println("2");
                pw.println("FILEDIA"); pw.println("0");
                pw.println("CMDDIA"); pw.println("0");
                pw.println("FONTALT"); pw.println("txt.shx");

                // 불필요한 DB 변경 및 렌더링 지연 방지
                pw.println("XLOADCTL"); pw.println("0");
                pw.println("DEMANDLOAD"); pw.println("0");
                pw.println("REGENMODE"); pw.println("0");

                pw.println("_.PLOT");
                pw.println("Y");                                       // 상세 플롯 구성 (Yes)
                pw.println("");                                        // 배치 이름 (기본값: 모형)
                pw.println("DWG To PDF.pc3");                          // 출력 장치
                pw.println("ISO_full_bleed_A3_(420.00_x_297.00_MM)");  // A3 용지
                pw.println("M");                                       // 단위 (Millimeters)
                pw.println("L");                                       // 방향 (Landscape)
                pw.println("N");                                       // 거꾸로 출력 (No)
                pw.println("E");                                       // 영역 (Extents)
                pw.println("F");                                       // 축척 (Fit)
                pw.println("C");                                       // 중심 (Center)
                pw.println("Y");                                       // 플롯 스타일 적용 (Yes)
                pw.println("acad.ctb");                                // 스타일 테이블 (컬러)
                pw.println("Y");                                       // 선 가중치 (Yes)
                pw.println("N");                                       // 음영 플롯 (No)
                pw.println(tempDestPdf.getAbsolutePath());             // [임시 PDF 출력 경로 주입]
                pw.println("N");                                       // 페이지 설정 저장 (No)
                pw.println("Y");                                       // 플롯 진행 (Yes)
                pw.println("QUIT");                                    // 종료
                pw.println("Y");                                       // 변경사항 버리기 승인
                pw.println();                                          // 마감 개행
            }

            // AcCoreConsole CLI Command 구성 (임시 DWG 파일 경로 전달)
            List<String> command = new ArrayList<>();
            command.add(autoCadExec.getAbsolutePath());
            command.add("/nologo");
            command.add("/i");
            command.add(tempSrcDwg.getAbsolutePath()); // [임시 영문 DWG 주입]
            command.add("/s");
            command.add(tempScript.getAbsolutePath());
            command.add("/l");
            command.add("en-US");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(autoCadExec.getParentFile());

            // 프로세스 버퍼 데드락 방지 (Windows NUL 디바이스 리다이렉트)
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File("NUL"));

            System.out.println(">> [AcCoreConsole 2027 가동] " + srcFile.getName() + " (임시파일명: " + tempSrcDwg.getName() + ")");

            Process process = pb.start();

            // 프로세스 완료 대기 및 타임아웃 가드
            boolean completed = process.waitFor(autoCadTimeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                System.err.println("ERROR: [타임아웃] AcCoreConsole 프로세스 강제 종료 (" + autoCadTimeoutSeconds + "초 초과): " + srcFile.getName());
                throw new TimeoutException("AutoCAD 변환 프로세스 시간 초과: " + srcFile.getName());
            }

            // =========================================================================
            // [개선사항 4] 변환된 PDF 파일이름을 임시변수(원본 파일명)로 복원
            // =========================================================================
            if (tempDestPdf.exists() && tempDestPdf.length() > 0) {
                if (destPdf.exists()) {
                    destPdf.delete();
                }
                Files.move(tempDestPdf.toPath(), destPdf.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println(">> [AcCoreConsole 변환 성공 및 파일명 복원 완료]: " + destPdf.getAbsolutePath());
                return true;
            }

            return false;

        } finally {
            // =========================================================================
            // 임시 자원 Clean-up (임시 DWG, 임시 PDF 잔여물, SCR 스크립트 삭제)
            // =========================================================================
            if (tempSrcDwg.exists()) {
                tempSrcDwg.delete();
            }
            if (tempDestPdf.exists()) {
                tempDestPdf.delete();
            }
            if (tempScript != null && tempScript.exists()) {
                tempScript.delete();
            }
        }
    }



    private static String detectFileVersion(File file, String ext) {
        return "표준 규격";
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
            pw.write("\uFEFF");
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

    private static File resolveRelativeOutputDir(File inputDir, File baseOutputDir, File sourceParentDir) {
        if (inputDir == null || baseOutputDir == null || sourceParentDir == null) {
            return baseOutputDir;
        }

        try {
            java.nio.file.Path inputPath = inputDir.getCanonicalFile().toPath();
            java.nio.file.Path sourceParentPath = sourceParentDir.getCanonicalFile().toPath();
            java.nio.file.Path relativePath = inputPath.relativize(sourceParentPath);
            return relativePath.toString().isEmpty()
                    ? baseOutputDir
                    : new File(baseOutputDir, relativePath.toString());
        } catch (Exception e) {
            System.err.println("WARNING: [출력 경로 계산 실패] 입력 폴더 기준 상대 경로를 계산하지 못해 기본 출력 폴더를 사용합니다: " + e.getMessage());
            return baseOutputDir;
        }
    }

    private static void moveSourceFileToOutputDir(File srcFile, File targetDir) {
        if (srcFile == null || targetDir == null || !srcFile.exists()) {
            return;
        }

        try {
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                System.err.println("WARNING: [원본 이동 실패] 출력 폴더 생성 실패: " + targetDir.getAbsolutePath());
                return;
            }

            File movedFile = createUniqueTargetFile(targetDir, srcFile.getName());
            boolean moved = false;

            // 1. OS 파일 핸들 해제 대기 및 Move 재시도 (최대 5회, 500ms 간격)
            for (int i = 0; i < 5; i++) {
                try {
                    Files.move(srcFile.toPath(), movedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    moved = true;
                    break;
                } catch (IOException e) {
                    try {
                        Thread.sleep(500); // Windows OS 핸들 해제 대기
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // 2. Move 최종 실패 시 Copy & Delete Fallback 처리
            if (!moved) {
                Files.copy(srcFile.toPath(), movedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!srcFile.delete()) {
                    srcFile.deleteOnExit(); // JVM 종료 시 삭제 예약
                }
            }

            System.out.println(">> [원본 이동 완료] " + srcFile.getName() + " -> " + movedFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("WARNING: [원본 이동 실패] " + srcFile.getName() + " -> " + e.getMessage());
        }
    }
    private static File createUniqueTargetFile(File targetDir, String fileName) {
        File targetFile = new File(targetDir, fileName);
        if (!targetFile.exists()) {
            return targetFile;
        }

        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        int sequence = 1;

        do {
            targetFile = new File(targetDir, baseName + "_" + sequence + extension);
            sequence++;
        } while (targetFile.exists());

        return targetFile;
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

    public static void loadProperties() {
        Properties prop = new Properties();
        boolean loaded = false;

        try {
            String codePath = ConverterMain.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            if (codePath != null) {
                File baseDir = new File(codePath).getParentFile();
                if (baseDir != null) {
                    File propFile = new File(baseDir, "config.properties");
                    if (propFile.exists()) {
                        try (InputStream is = new FileInputStream(propFile)) {
                            prop.load(is);
                            loaded = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }

        if (!loaded) {
            File propFile = new File("config.properties");
            if (propFile.exists()) {
                try (InputStream is = new FileInputStream(propFile)) {
                    prop.load(is);
                    loaded = true;
                } catch (IOException e) {
                    System.err.println("WARNING: config.properties 외부 파일을 찾았으나 읽기에 실패했습니다: " + e.getMessage());
                }
            }
        }

        if (!loaded) {
            try (InputStream is = ConverterMain.class.getResourceAsStream("/config.properties")) {
                if (is != null) {
                    prop.load(is);
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
        libreOfficeProfilePath = prop.getProperty("converter.libreoffice.profile.path", "C:\\Program Files\\LibreOffice");
        autoCadPath = prop.getProperty("converter.autocad.path", "C:\\Program Files\\Autodesk\\AutoCAD 2027\\accoreconsole.exe");
        autoCadTimeoutSeconds = Integer.parseInt(prop.getProperty("converter.autocad.timeout.seconds", "120"));
        inputDirSetting = prop.getProperty("converter.input.dir", "");
        outputDirSetting = prop.getProperty("converter.output.dir", "");
        logDirSetting = prop.getProperty("converter.log.dir", "C:\\IPLMS\\95_logs");
        guiLogMaxLength = Integer.parseInt(prop.getProperty("converter.gui.log.max.length", "500000"));
        memoryLimitBytes = Long.parseLong(prop.getProperty("converter.memory.limit.bytes", String.valueOf(2L * 1024 * 1024 * 1024)));
        timeoutSeconds = Integer.parseInt(prop.getProperty("converter.timeout.seconds", "90"));
        reportExcelName = prop.getProperty("converter.report.excel.name", "conversion_report.csv");
        daemonIntervalMinutes = Integer.parseInt(prop.getProperty("daemon.interval.minutes", "10"));
        serverPort = Integer.parseInt(prop.getProperty("converter.server.port", "9119"));
    }

    public static int getDaemonIntervalMinutes() {
        return daemonIntervalMinutes;
    }

    public static String getOutputDirSetting() {
        if (outputDirSetting == null || outputDirSetting.trim().isEmpty()) {
            return inputDirSetting != null ? inputDirSetting.trim() : "";
        }
        return outputDirSetting.trim();
    }

    public static String getLogDirSetting() {
        if (logDirSetting == null || logDirSetting.trim().isEmpty()) {
            return logDirSetting != null ? logDirSetting.trim() : "";
        }
        return logDirSetting.trim();
    }

    public static void startHttpServer() {
        if (httpServer != null) {
            System.out.println(">> [HttpServer] 서버가 이미 실행 중입니다.");
            return;
        }
        try {
            httpServer = HttpServer.create(new InetSocketAddress(serverPort), 0);
            httpServer.createContext("/api/convert", new ConvertHandler());
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            System.out.println(">> [HttpServer] 내장 웹 서버가 포트 " + serverPort + "에서 실행 중입니다.");
        } catch (Exception e) {
            System.err.println("ERROR: [HttpServer] 서버 시작 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void stopHttpServer() {
        if (httpServer != null) {
            System.out.println(">> [HttpServer] 서버 종료 중...");
            httpServer.stop(2);
            httpServer = null;
            System.out.println(">> [HttpServer] 서버가 성공적으로 종료되었습니다.");
        }
    }

    private static class ConvertHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
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
                } else {
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
                String outputDirStr = getOutputDirSetting();
                File targetDir = (outputDirStr != null && !outputDirStr.trim().isEmpty())
                        ? new File(outputDirStr.trim())
                        : srcFile.getParentFile();
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                File destPdf = new File(targetDir, baseName + ".pdf");
                File destTxt = new File(targetDir, baseName + ".txt");

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
                    String textContent = "";
                    if (isTxtExtracted && destTxt.exists()) {
                        try {
                            textContent = new String(java.nio.file.Files.readAllBytes(destTxt.toPath()), StandardCharsets.UTF_8);
                        } catch (Exception ignored) {
                        }
                    }

                    String jsonResponse = String.format(
                            "{\"status\":\"success\", \"pdfPath\":\"%s\", \"txtPath\":\"%s\", \"txtExtracted\":%b, \"extractedTextContent\":\"%s\", \"elapsedTime\":\"%.2f초\"}",
                            escapeJson(destPdf.getAbsolutePath()),
                            escapeJson(destTxt.getAbsolutePath()),
                            isTxtExtracted,
                            escapeJson(textContent),
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
                }
            }
            return result;
        }

        private String parseJsonFilePath(String json) {
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
            return escapedPath.replace("\\\\", "\\").replace("\\\"", "\"");
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
            return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t");
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