package com.iplms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ConverterMain {

    private static String libreOfficePath;
    private static String inputDirSetting;
    private static String outputDirSetting;
    private static int timeoutSeconds;
    private static String reportExcelName;

    // 멀티스레드 환경에서 순서 무관하게 안전하게 행 데이터를 수집하는 Thread-Safe 큐
    private static final ConcurrentLinkedQueue<ReportRow> reportQueue = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) {
        System.out.println("📂 [System 환경 정보] 현재 실행 경로 (User Dir): " + System.getProperty("user.dir"));

        loadProperties();

        if (inputDirSetting == null || inputDirSetting.trim().isEmpty()) {
            System.err.println("❌ 오류: config.properties 파일에 converter.input.dir 설정이 누락되었거나 비어있습니다.");
            return;
        }

        File inputDir = new File(inputDirSetting.trim());
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            System.err.println("❌ 오류: 설정된 입력 폴더가 존재하지 않거나 디렉토리가 아닙니다 -> " + inputDir.getAbsolutePath());
            return;
        }

        System.out.println("🚀 [IPLMS Hybrid Converter] 폴더 탐색 및 멀티스레드 엔진 가동 개시");
        System.out.println("📌 탐색 대상 입력 폴더: " + inputDir.getAbsolutePath());
        System.out.println("📌 설정된 출력 폴더: " + (outputDirSetting.isEmpty() ? "원본 파일과 동일 경로" : outputDirSetting));
        System.out.println("📌 LibreOffice 경로: " + libreOfficePath);

        List<File> targetFiles = new ArrayList<>();
        scanDirectory(inputDir, targetFiles);

        if (targetFiles.isEmpty()) {
            System.out.println("⏭️ [알림] 입력 폴더 이하에서 변환 가능한 대상 문서를 찾지 못했습니다.");
            return;
        }

        System.out.println("📊 [탐색 완료] 총 " + targetFiles.size() + "개의 대상 문서가 수집되었습니다. 스케줄러 풀로 진입합니다.");

        int threadCount = Integer.parseInt(System.getProperty("converter.threads", "2"));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        File targetDir = null;
        if (outputDirSetting != null && !outputDirSetting.trim().isEmpty()) {
            targetDir = new File(outputDirSetting.trim());
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
        }

        for (File srcFile : targetFiles) {
            final File finalTargetDir = (targetDir != null) ? targetDir : srcFile.getParentFile();

            executor.submit(() -> {
                String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
                File destPdf = new File(finalTargetDir, baseName + ".pdf");
                File destTxt = new File(finalTargetDir, baseName + ".txt");

                if (destPdf.exists()) {
                    System.out.println("♻️ [덮어쓰기] 기존 PDF 파일 제거 및 갱신: " + destPdf.getName());
                    destPdf.delete();
                }
                if (destTxt.exists()) {
                    destTxt.delete();
                }

                String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();
                String fileVersion = detectFileVersion(srcFile, ext);

                // 📌 [신규 추가] 원본 파일 크기를 바이트 단위로 구한 뒤 KB 단위로 환산 (소수점 2자리 정밀 포맷)
                double fileSizeKb = srcFile.length() / 1024.0;
                String formattedSize = String.format("%.2f", fileSizeKb);

                ReportRow rowData = new ReportRow();
                rowData.filePath = srcFile.getAbsolutePath();
                rowData.fileName = srcFile.getName();
                rowData.fileType = ext.toUpperCase() + " (" + fileVersion + ")";
                rowData.fileSize = formattedSize; // 데이터 적재

                long startTime = System.nanoTime();

                ExecutorService singleTaskExecutor = Executors.newSingleThreadExecutor();
                Future<Boolean> future = singleTaskExecutor.submit(() -> {
                    boolean isConverted = convertToPdf(srcFile, destPdf, fileVersion);
                    if (isConverted && destPdf.exists()) {
                        rowData.pdfResult = "성공";
                        boolean isExtracted = extractTextFromPdf(destPdf, destTxt);
                        rowData.txtResult = isExtracted ? "성공" : "실패";
                        return true;
                    } else {
                        rowData.pdfResult = "실패";
                        rowData.txtResult = "실패 (PDF 변환 실패됨)";
                        return false;
                    }
                });

                try {
                    future.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    System.err.println("⏰ [타임아웃] 변환 시간 초과 (" + timeoutSeconds + "초): " + srcFile.getName());
                    future.cancel(true);
                    writeErrorFile(srcFile, "변환 시간 초과 (" + timeoutSeconds + "초)", finalTargetDir);
                    rowData.pdfResult = "실패 (타임아웃)";
                    rowData.txtResult = "실패";
                } catch (Exception e) {
                    System.err.println("❌ [런타임 에러]: " + srcFile.getName());
                    writeErrorFile(srcFile, e.getMessage(), finalTargetDir);
                    rowData.pdfResult = "실패 (에러)";
                    rowData.txtResult = "실패";
                } finally {
                    long endTime = System.nanoTime();
                    double elapsedTimeSeconds = (endTime - startTime) / 1_000_000_000.0;
                    rowData.elapsedTime = String.format("%.2f", elapsedTimeSeconds);

                    // 📌 [요구사항 반영] 변환 종료 로그 출력에 파일 용량(KB) 지표 동적 결합
                    System.out.println("🏁 [변환 종료 완료] 파일명: " + srcFile.getName()
                            + " | 용량: " + rowData.fileSize + " KB"
                            + " | 소요시간: " + rowData.elapsedTime + "초");

                    reportQueue.add(rowData);
                    singleTaskExecutor.shutdownNow();
                }
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        // 모든 멀티스레드 완료 시점에 최종 CSV 리포트 출력
        generateCsvReport(targetDir != null ? targetDir : inputDir);

        System.out.println("🏁 [IPLMS Hybrid Converter] 모든 디렉토리 대기열 처리 및 CSV 리포트 저장 완료");
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
                        name.endsWith(".hwp") || name.endsWith(".hwpx")) {
                    resultList.add(file);
                }
            }
        }
    }

    private static boolean convertToPdf(File srcFile, File destPdf, String fileVersion) throws Exception {
        String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();
        System.out.println("🔄 [변환 시작] 포맷: [" + ext.toUpperCase() + "] | 문서 버전: [" + fileVersion + "] | 파일명: " + srcFile.getName());

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

        int exitCode;
        synchronized (ConverterMain.class) {
            Process process = pb.start();
            exitCode = process.waitFor();
        }

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

    private static String detectFileVersion(File file, String ext) {
        try {
            if ("docx".equals(ext) || "xlsx".equals(ext) || "pptx".equals(ext) || "hwpx".equals(ext)) {
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (("docx".equals(ext) || "xlsx".equals(ext) || "pptx".equals(ext))
                                && "docProps/app.xml".equalsIgnoreCase(entry.getName())) {
                            return parseTagValueFromStream(zis, "AppVersion", "MS Office 계열");
                        }
                        if ("hwpx".equals(ext) && "META-INF/manifest.xml".equalsIgnoreCase(entry.getName())) {
                            return parseTagValueFromStream(zis, "version", "개방형 HWPX");
                        }
                    }
                }
            } else if ("hwp".equals(ext)) {
                try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                    byte[] headerBytes = new byte[64];
                    dis.readFully(headerBytes);
                    if ((headerBytes[0] == (byte)0xD0 && headerBytes[1] == (byte)0xCF && headerBytes[2] == (byte)0x11) ||
                            new String(headerBytes, 0, 3, StandardCharsets.UTF_8).contains("HWP")) {

                        int vMajor = headerBytes[39] & 0xFF;
                        int vMinor = headerBytes[38] & 0xFF;
                        int vBuild = headerBytes[37] & 0xFF;
                        int vRevision = headerBytes[36] & 0xFF;

                        if (vMajor >= 5) {
                            return "v" + vMajor + "." + vMinor + "." + vBuild + "." + vRevision;
                        }
                    }
                    return "v5.0 미만 구형";
                }
            } else if ("doc".equals(ext) || "xls".equals(ext) || "ppt".equals(ext)) {
                return "97-2003 바이너리";
            }
        } catch (Exception e) {
            return "식별 실패";
        }
        return "표준 규격";
    }

    private static String parseTagValueFromStream(InputStream is, String key, String defaultPrefix) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        String content = new String(bos.toByteArray(), StandardCharsets.UTF_8);

        if (content.contains(key)) {
            if ("AppVersion".equals(key)) {
                int start = content.indexOf("<AppVersion>");
                int end = content.indexOf("</AppVersion>");
                if (start != -1 && end != -1 && end > start) {
                    String verNum = content.substring(start + 12, end).trim();
                    if (verNum.startsWith("16")) return "2016/365 (" + verNum + ")";
                    if (verNum.startsWith("15")) return "2013 (" + verNum + ")";
                    if (verNum.startsWith("14")) return "2010 (" + verNum + ")";
                    if (verNum.startsWith("12")) return "2007 (" + verNum + ")";
                    return "v" + verNum;
                }
            } else if ("version".equals(key)) {
                int idx = content.indexOf("version=\"");
                if (idx == -1) idx = content.indexOf("version='");
                if (idx != -1) {
                    int start = idx + 9;
                    int end = content.indexOf(content.charAt(idx + 8) == '"' ? "\"" : "'", start);
                    if (end > start) {
                        return "표준 v" + content.substring(start, end).trim();
                    }
                }
            }
        }
        return defaultPrefix;
    }

    private static boolean extractTextFromPdf(File pdfFile, File destTxt) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(destTxt), StandardCharsets.UTF_8))) {
                writer.write(text);
                writer.flush();
            }
            System.out.println("📝 [텍스트 추출 완료]: " + destTxt.getName());
            return true;
        } catch (Exception e) {
            System.err.println("⚠️ [텍스트 추출 실패 - 패스]: " + pdfFile.getName() + " (" + e.getMessage() + ")");
            return false;
        }
    }

    /**
     * 최종 CSV 리포트 생성기 (파일용량 KB 컬럼 안정 추가 완료)
     */
    private static void generateCsvReport(File exportFolder) {
        File csvFile = new File(exportFolder, reportExcelName);
        if (csvFile.exists()) {
            csvFile.delete();
        }

        System.out.println("📊 [CSV 내보내기 개시] 최종 리포트를 작성합니다 -> " + csvFile.getAbsolutePath());

        int index = 1;

        try (FileOutputStream fos = new FileOutputStream(csvFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            bos.write(new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF});

            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(bos, StandardCharsets.UTF_8))) {

                // 📌 [수정] 파일용량(KB) 컬럼 헤더 안정 바인딩
                pw.println("번호,파일경로,파일명,파일종류,pdf변환결과(성공/실패),텍스트추출결과,파일용량(KB),소요시간(초)");

                // 수집 데이터 출력 (BOM 및 콤마 가드 적용)
                for (ReportRow row : reportQueue) {
                    pw.print(index++ + ",");
                    pw.print(escapeCsv(row.filePath) + ",");
                    pw.print(escapeCsv(row.fileName) + ",");
                    pw.print(escapeCsv(row.fileType) + ",");
                    pw.print(escapeCsv(row.pdfResult) + ",");
                    pw.print(escapeCsv(row.txtResult) + ",");
                    pw.print(escapeCsv(row.fileSize) + ","); // 📌 파일 용량 데이터 출력
                    pw.println(escapeCsv(row.elapsedTime));
                }
                pw.flush();
            }
            System.out.println("✅ [CSV 리포트 생성 완료] 총 " + (index - 1) + "건의 변환 이력 저장 완료.");
        } catch (Exception e) {
            System.err.println("❌ CSV 보고서 생성 도중 물리적 디스크 에러가 발생했습니다: " + e.getMessage());
        }
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static void writeErrorFile(File srcFile, String errMsg, File targetDir) {
        String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
        File errFile = new File(targetDir, baseName + "_ERR.txt");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(errFile), StandardCharsets.UTF_8))) {
            pw.println("=====================================================");
            pw.println("❌ IPLMS 변환 오류 리포트");
            pw.println("=====================================================");
            pw.println("대상 원본 파일: " + srcFile.getAbsolutePath());
            pw.println("발생 시각: " + new java.util.Date());
            pw.println("오류 세부 명세: " + errMsg);
            pw.flush();
        } catch (Exception e) {
            System.err.println("❌ 에러 로그 파일 쓰기 실패: " + e.getMessage());
        }
    }

    private static void loadProperties() {
        Properties prop = new Properties();
        File propFile = null;

        try {
            String codePath = ConverterMain.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            if (codePath != null) {
                File fileOrDir = new File(codePath);
                File baseDir = fileOrDir.isDirectory() ? fileOrDir : fileOrDir.getParentFile();
                propFile = new File(baseDir, "config.properties");
            }
        } catch (Exception e) {
            propFile = new File("config.properties");
        }

        if (propFile != null && propFile.exists()) {
            try (InputStream is = new FileInputStream(propFile)) {
                prop.load(is);
                System.out.println("✅ [설정 로드 성공] 파일 절대 경로: " + propFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("⚠️ config.properties 파일은 찾았으나 읽기에 실패했습니다: " + e.getMessage());
            }
        }

        libreOfficePath = prop.getProperty("converter.libreoffice.path", "C:\\Program Files\\LibreOffice\\program\\soffice.exe");
        inputDirSetting = prop.getProperty("converter.input.dir", "");
        outputDirSetting = prop.getProperty("converter.output.dir", "");
        timeoutSeconds = Integer.parseInt(prop.getProperty("converter.timeout.seconds", "30"));
        reportExcelName = prop.getProperty("converter.report.excel.name", "conversion_report.csv");

        System.setProperty("converter.threads", prop.getProperty("converter.thread.count", "2"));
    }

    private static class ReportRow {
        String filePath;
        String fileName;
        String fileType;
        String pdfResult;
        String txtResult;
        String elapsedTime;
        String fileSize; // 📌 신규 추가
    }
}