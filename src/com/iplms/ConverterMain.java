package com.iplms;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;
// hwplib의 텍스트 추출 옵션 ENUM을 임포트합니다.
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;

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

        System.out.println("🚀 [IPLMS Hybrid Converter] 멀티스레드 엔진 가동 개시 (오피스 & 한글 통합판)");
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

                // 스레드 풀에 개별 태스크 제출 (격리 실행)
                executor.submit(() -> {
                    File srcFile = new File(trimmedPath);
                    if (!srcFile.exists()) {
                        System.err.println("❌ 파일을 찾을 수 없음 (Skip): " + trimmedPath);
                        return;
                    }

                    // 📌 [설정 파일 연동 보완] config.properties의 출력 경로 검증 및 물리 폴더 확보
                    File targetDir;
                    if (outputDirSetting != null && !outputDirSetting.trim().isEmpty()) {
                        targetDir = new File(outputDirSetting.trim());
                        if (!targetDir.exists()) {
                            targetDir.mkdirs(); // 폴더가 없으면 자동 생성
                        }
                    } else {
                        targetDir = srcFile.getParentFile();
                    }

                    String baseName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));
                    File destPdf = new File(targetDir, baseName + ".pdf");
                    File destTxt = new File(targetDir, baseName + ".txt");

                    // [덮어쓰기 가드] 기존 파일이 있을 경우, 사전 물리적 삭제 진행
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
                        // 1. PDF 변환 파이프라인 작동 (무조건 새로 덮어씀)
                        boolean isConverted = convertToPdf(srcFile, destPdf);
                        if (isConverted && destPdf.exists()) {
                            // 2. 텍스트 추출 파이프라인 작동
                            extractTextFromPdf(destPdf, destTxt);
                            return true;
                        }
                        return false;
                    });

                    try {
                        future.get(timeoutSeconds, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        System.err.println("⏰ [타임아웃 제한 시간 초과] 강제 인터럽트: " + srcFile.getName());
                        future.cancel(true);
                        writeErrorFile(srcFile, "변환 시간 초과 (" + timeoutSeconds + "초)");
                    } catch (Exception e) {
                        System.err.println("❌ [런타임 에러 발생]: " + srcFile.getName());
                        writeErrorFile(srcFile, e.getMessage());
                    } finally {
                        singleTaskExecutor.shutdownNow();
                    }
                });
            }

        } catch (IOException e) {
            System.err.println("❌ 대상 파일 리스트 텍스트를 읽는데 실패했습니다: " + e.getMessage());
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
            System.out.println("🏁 [IPLMS Hybrid Converter] 모든 대기열 처리 프로세스 종료");
        }
    }

    /**
     * 확장자 분기별 PDF 변환 제어 핸들러
     */
    private static boolean convertToPdf(File srcFile, File destPdf) throws Exception {
        String ext = srcFile.getName().substring(srcFile.getName().lastIndexOf(".") + 1).toLowerCase();

        System.out.println("🔄 [변환 시작] 포맷: [" + ext.toUpperCase() + "] 파일명: " + srcFile.getName());

        switch (ext) {
            // ① MS 오피스 3종 -> LibreOffice CLI 위임 파이프라인
            case "docx": case "doc":
            case "xlsx": case "xls":
            case "pptx": case "ppt":
                return convertOfficeUsingLibre(srcFile, destPdf);

            // ② 아래아한글 -> 순수 자바 파이프라인 (hwplib + iText7)
            case "hwp":
                return convertHwpUsingJava(srcFile, destPdf);

            default:
                throw new IllegalArgumentException("지원하지 않는 파일 포맷 확장자입니다: " + ext);
        }
    }

    /**
     * [엔진 1] LibreOffice Headless 프로세스 실행기 (오피스 전용)
     */
    private static boolean convertOfficeUsingLibre(File srcFile, File destPdf) throws Exception {
        File outputDir = destPdf.getParentFile();

        // CLI 커맨드 빌드: soffice --headless --convert-to pdf --outdir [설정된출력폴더] [원본경로]
        ProcessBuilder pb = new ProcessBuilder(
                libreOfficePath,
                "--headless",
                "--convert-to", "pdf",
                "--outdir", outputDir.getAbsolutePath(),
                srcFile.getAbsolutePath()
        );

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            // LibreOffice 기본 출력 파일명(기본적으로 원본과 동일한 폴더에 원본명으로 생성됨)을
            // 최종 목적지 파일명(destPdf)으로 리네임 및 이동 처리
            String defaultGeneratedName = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.')) + ".pdf";
            File generatedPdf = new File(outputDir, defaultGeneratedName);

            if (generatedPdf.exists()) {
                if (!generatedPdf.getAbsolutePath().equals(destPdf.getAbsolutePath())) {
                    if(destPdf.exists()) destPdf.delete();
                    generatedPdf.renameTo(destPdf);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * [엔진 2] Hwplib + iText7 조합 한글 변환기
     */
    private static boolean convertHwpUsingJava(File srcFile, File destPdf) throws Exception {
        HWPFile hwpFile = HWPReader.fromFile(srcFile);
        if (hwpFile == null) return false;

        String extractedText = TextExtractor.extract(hwpFile, TextExtractMethod.AppendControlTextAfterParagraphText);
        try (PdfWriter writer = new PdfWriter(destPdf);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {

            // 시스템 기본 바탕체 폰트 매핑 (한글 깨짐 방지)
            String fontPath = "C:\\Windows\\Fonts\\batang.ttc,0";
            File fontFile = new File("C:\\Windows\\Fonts\\batang.ttc");
            if(!fontFile.exists()) {
                fontPath = com.itextpdf.io.font.constants.StandardFonts.HELVETICA; // 폴백
            }

            PdfFont font = PdfFontFactory.createFont(fontPath, PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED);
            document.setFont(font);

            String[] lines = extractedText.split("\n");
            for (String line : lines) {
                document.add(new Paragraph(line.trim().isEmpty() ? " " : line));
            }
            document.flush();
            return true;
        }
    }

    /**
     * [공통 모듈] Apache PDFBox 기반 고속 텍스트 스크랩 엔진
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
     * [예외 방어 메커니즘] 실패 시 원본 파일 옆에 에러 명세 보고서 기록
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
     * 환경 설정 로드 및 JVM 설정값 주입
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

        // 윈도우 기본 디폴트 설치 경로 가드 지정
        libreOfficePath = prop.getProperty("converter.libreoffice.path", "C:\\Program Files\\LibreOffice\\program\\soffice.exe");
        outputDirSetting = prop.getProperty("converter.output.dir", "");
        timeoutSeconds = Integer.parseInt(prop.getProperty("converter.timeout.seconds", "30"));

        System.setProperty("converter.threads", prop.getProperty("converter.thread.count", "2"));
    }
}