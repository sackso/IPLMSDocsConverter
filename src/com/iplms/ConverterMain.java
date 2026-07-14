package com.iplms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

public class ConverterMain {

    private static String libreOfficePath;
    private static String inputDirSetting;
    private static String outputDirSetting;
    private static int timeoutSeconds;

    public static void main(String[] args) {
        // 📌 1. 시작 시 현재 프로그램이 바라보는 시스템 기준 실행 경로(Working Directory) 선언
        System.out.println("📂 [System 환경 정보] 현재 실행 경로 (User Dir): " + System.getProperty("user.dir"));

        loadProperties();

        // 입력 폴더 설정 검증
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

        // 📌 2. 입력 폴더 이하의 모든 파일 재귀적(Recursive) 수집 파이프라인 기동
        List<File> targetFiles = new ArrayList<>();
        scanDirectory(inputDir, targetFiles);

        if (targetFiles.isEmpty()) {
            System.out.println("⏭️ [알림] 입력 폴더 이하에서 변환 가능한 대상 문서(docx, doc, xlsx, xls, pptx, ppt, hwp)를 찾지 못했습니다.");
            return;
        }

        System.out.println("📊 [탐색 완료] 총 " + targetFiles.size() + "개의 대상 문서가 수집되었습니다. 스케줄러 풀로 진입합니다.");

        // 설정값 기반 고정 스레드 풀 생성
        int threadCount = Integer.parseInt(System.getProperty("converter.threads", "2"));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // 출력 디렉토리 사전 검증 및 물리 폴더 확보
        File targetDir = null;
        if (outputDirSetting != null && !outputDirSetting.trim().isEmpty()) {
            targetDir = new File(outputDirSetting.trim());
            if (!targetDir.exists()) {
                targetDir.mkdirs(); // 출력 폴더가 없으면 자동 생성
            }
        }

        for (File srcFile : targetFiles) {
            final File finalTargetDir = (targetDir != null) ? targetDir : srcFile.getParentFile();

            // 스레드 풀에 개별 태스크 제출 (격리 실행)
            executor.submit(() -> {
                String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
                File destPdf = new File(finalTargetDir, baseName + ".pdf");
                File destTxt = new File(finalTargetDir, baseName + ".txt");

                // [덮어쓰기 가드] 기존 결과 파일이 있을 경우, 사전 물리적 삭제 진행
                if (destPdf.exists()) {
                    System.out.println("♻️ [덮어쓰기] 기존 PDF 파일 제거 및 갱신: " + destPdf.getName());
                    destPdf.delete();
                }
                if (destTxt.exists()) {
                    destTxt.delete();
                }

                // 타임아웃 가드(Future) 구동으로 행 걸림(Hang) 원천 방지
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
                    writeErrorFile(srcFile, "변환 시간 초과 (" + timeoutSeconds + "초)", finalTargetDir);
                } catch (Exception e) {
                    System.err.println("❌ [런타임 에러]: " + srcFile.getName());
                    writeErrorFile(srcFile, e.getMessage(), finalTargetDir);
                } finally {
                    singleTaskExecutor.shutdownNow();
                }
            });
        }

        // 스레드 정상 종료 대기 흐름 제어
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        System.out.println("🏁 [IPLMS Hybrid Converter] 모든 디렉토리 대기열 처리 프로세스 완료");
    }

    /**
     * 📌 [신규 추가] 디렉토리를 재귀적으로 순회하며 대상 확장자를 가진 파일들만 수집하는 메서드
     */
    private static void scanDirectory(File dir, List<File> resultList) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // 하위 폴더인 경우 파고 들어감 (재귀 호출)
                scanDirectory(file, resultList);
            } else {
                String name = file.getName().toLowerCase();
                // 오피스 문서 및 한글 문서 확장자 필터링
                if (name.endsWith(".docx") || name.endsWith(".doc") ||
                        name.endsWith(".xlsx") || name.endsWith(".xls") ||
                        name.endsWith(".pptx") || name.endsWith(".ppt") ||
                        name.endsWith(".hwp")) {
                    resultList.add(file);
                }
            }
        }
    }

    /**
     * 확장자 분기별 PDF 변환 제어 핸들러
     */
    private static synchronized boolean convertToPdf(File srcFile, File destPdf) throws Exception {
        String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();

        System.out.println("🔄 [변환 시작] 포맷: [" + ext.toUpperCase() + "] 파일명: " + srcFile.getName());

        File outputDir = destPdf.getParentFile();
        ProcessBuilder pb;

        if ("hwp".equals(ext)) {
            // 한글 파일인 경우 HWP2002 전용 입력 필터(--infilter) 주입
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
            // 산출된 PDF 리네임 및 이동 정렬
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
     * 오류 로그 작성기 (에러 파일도 출력 디렉토리로 생성 위치 보정)
     */
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

    /**
     * 실행 중인 클래스(또는 JAR 파일)와 '동일한 위치'에서 config.properties를 탐색 및 로드
     */
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
        } else {
            System.err.println("⚠️ [경고] config.properties 파일을 찾지 못했습니다. 기본 내장 매핑 값으로 기동합니다.");
            if (propFile != null) {
                System.err.println("🔍 누락된 탐색 대상 경로: " + propFile.getAbsolutePath());
            }
        }

        libreOfficePath = prop.getProperty("converter.libreoffice.path", "C:\\Program Files\\LibreOffice\\program\\soffice.exe");
        inputDirSetting = prop.getProperty("converter.input.dir", "");
        outputDirSetting = prop.getProperty("converter.output.dir", "");
        timeoutSeconds = Integer.parseInt(prop.getProperty("converter.timeout.seconds", "30"));

        System.setProperty("converter.threads", prop.getProperty("converter.thread.count", "2"));
    }
}