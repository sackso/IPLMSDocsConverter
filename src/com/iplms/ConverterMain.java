package com.iplms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

public class ConverterMain {

    private static String libreOfficePath;
    private static String outputDirSetting;
    private static int timeoutSeconds;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("❌ 사용법: java -jar iplms-converter.jar <target_list_txt_path>");
            return;
        }

        String targetListPath = args[0];
        loadProperties();

        System.out.println("🚀 [IPLMS Hybrid Converter] 단일 LibreOffice 가속 엔진 가동 개시");
        System.out.println("📌 LibreOffice 경로: " + libreOfficePath);
        System.out.println("📌 설정된 출력 폴더: " + (outputDirSetting.isEmpty() ? "원본 파일과 동일 경로" : outputDirSetting));

        // 설정값 기반 고정 스레드 풀 생성
        int threadCount = Integer.parseInt(System.getProperty("converter.threads", "2"));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            List<String> filePaths = Files.readAllLines(Paths.get(targetListPath), StandardCharsets.UTF_8);

            for (String filePath : filePaths) {
                String trimmedPath = filePath.trim();
                if (trimmedPath.isEmpty() || trimmedPath.startsWith("#")) continue;

                executor.submit(() -> {
                    File srcFile = new File(trimmedPath);
                    if (!srcFile.exists()) {
                        System.err.println("❌ 파일을 찾을 수 없음 (Skip): " + trimmedPath);
                        return;
                    }

                    // 출력 경로 검증 및 폴더 자동 생성
                    File targetDir;
                    if (outputDirSetting != null && !outputDirSetting.trim().isEmpty()) {
                        targetDir = new File(outputDirSetting.trim());
                        if (!targetDir.exists()) {
                            targetDir.mkdirs();
                        }
                    } else {
                        targetDir = srcFile.getParentFile();
                    }

                    String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
                    File destPdf = new File(targetDir, baseName + ".pdf");
                    File destTxt = new File(targetDir, baseName + ".txt");

                    // [덮어쓰기 가드] 기존 파일 사전 삭제
                    if (destPdf.exists()) {
                        System.out.println("♻️ [덮어쓰기] 기존 PDF 파일 제거 및 갱신: " + destPdf.getName());
                        destPdf.delete();
                    }
                    if (destTxt.exists()) {
                        destTxt.delete();
                    }

                    // 타임아웃 가드(Future)
                    ExecutorService singleTaskExecutor = Executors.newSingleThreadExecutor();
                    Future<Boolean> future = singleTaskExecutor.submit(() -> {
                        // 1. 오피스 및 한글 공통 PDF 변환
                        boolean isConverted = convertToPdf(srcFile, destPdf);
                        if (isConverted && destPdf.exists()) {
                            // 2. 변환된 PDF에서 깨끗하게 텍스트 추출
                            extractTextFromPdf(destPdf, destTxt);
                            return true;
                        }
                        return false;
                    });

                    try {
                        future.get(timeoutSeconds, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        System.err.println("⏰ [타임아웃] 변환 시간 초과 (" + timeoutSeconds + "초): " + srcFile.getName());
                        future.cancel(true);
                        writeErrorFile(srcFile, "변환 시간 초과 (" + timeoutSeconds + "초)");
                    } catch (Exception e) {
                        System.err.println("❌ [런타임 에러]: " + srcFile.getName());
                        writeErrorFile(srcFile, e.getMessage());
                    } finally {
                        singleTaskExecutor.shutdownNow();
                    }
                });
            }

        } catch (IOException e) {
            System.err.println("❌ 대상 리스트 파일을 읽는 데 실패했습니다: " + e.getMessage());
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
            System.out.println("🏁 [IPLMS Hybrid Converter] 대기열 처리 프로세스 종료");
        }
    }

    /**
     * 모든 문서를 LibreOffice로 일괄 처리하는 통합 변환 컨트롤러
     */
    private static boolean convertToPdf(File srcFile, File destPdf) throws Exception {
        String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();
        System.out.println("🔄 [변환 시작] 포맷: [" + ext.toUpperCase() + "] 파일명: " + srcFile.getName());

        File outputDir = destPdf.getParentFile();
        ProcessBuilder pb;

        if ("hwp".equals(ext)) {
            // 📌 [핵심] 한글 파일인 경우, 앞서 추가한 HWP2002 전용 입력 필터(--infilter)를 명시적으로 주입합니다.
            pb = new ProcessBuilder(
                    libreOfficePath,
                    "--headless",
                    "--infilter=Hwp2002_File",
                    "--convert-to", "pdf:writer_pdf_Export",
                    "--outdir", outputDir.getAbsolutePath(),
                    srcFile.getAbsolutePath()
            );
        } else {
            // MS 오피스 계열 변환
            pb = new ProcessBuilder(
                    libreOfficePath,
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", outputDir.getAbsolutePath(),
                    srcFile.getAbsolutePath()
            );
        }

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            // 산출된 PDF 리네임 및 이동 가드
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
     * PDFBox 기반 고속 텍스트 추출 엔진
     */
    private static void extractTextFromPdf(File pdfFile, File destTxt) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(destTxt), StandardCharsets.UTF_8))) {
                writer.write(text);
                writer.flush();
            }
            System.out.println("📝 [텍스트 추출 완료]: " + destTxt.getName());
        } catch (Exception e) {
            System.err.println("⚠️ [텍스트 추출 실패 - 패스]: " + pdfFile.getName() + " (" + e.getMessage() + ")");
        }
    }

    /**
     * 오류 로그 작성기
     */
    private static void writeErrorFile(File srcFile, String errMsg) {
        String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
        File errFile = new File(srcFile.getParentFile(), baseName + "_ERR.txt");
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

    /**
     * 환경 설정 로드
     */
    private static void loadProperties() {
        Properties prop = new Properties();
        File propFile = new File("config.properties");

        if (propFile.exists()) {
            try (InputStream is = new FileInputStream(propFile)) {
                prop.load(is);
            } catch (IOException e) {
                System.err.println("⚠️ config.properties 로드 실패, 기본값으로 대체합니다.");
            }
        }

        libreOfficePath = prop.getProperty("converter.libreoffice.path", "C:\\Program Files\\LibreOffice\\program\\soffice.exe");
        outputDirSetting = prop.getProperty("converter.output.dir", "");
        timeoutSeconds = Integer.parseInt(prop.getProperty("converter.timeout.seconds", "30"));

        System.setProperty("converter.threads", prop.getProperty("converter.thread.count", "2"));
    }
}