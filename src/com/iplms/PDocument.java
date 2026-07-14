package com.iplms;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 문서 변환 및 텍스트 추출을 위한 상위 추상 클래스
 * @author shpark
 */
public abstract class PDocument {
    protected static String hwpExePath;
    protected static String outputDir;
    protected static long timeoutSeconds;

    // Properties 초기화
    static {
        Properties prop = new Properties();
        String propFileName = "config.properties";
        try (InputStream inputStream = PDocument.class.getClassLoader().getResourceAsStream(propFileName)) {
            if (inputStream != null) {
                prop.load(inputStream);
                hwpExePath = prop.getProperty("converter.hwp.exe.path");
                outputDir = prop.getProperty("converter.output.dir", "").trim();
                timeoutSeconds = Long.parseLong(prop.getProperty("converter.timeout.seconds", "30"));
                System.out.println("[설정 로드 완료] HWP 경로: " + hwpExePath + ", 출력폴더: " + (outputDir.isEmpty() ? "원본동일" : outputDir) + ", 타임아웃: " + timeoutSeconds + "초");
            } else {
                System.err.println("[경고] config.properties를 찾을 수 없어 기본값으로 세팅합니다.");
                setFallbackConfig();
            }
        } catch (Exception e) {
            System.err.println("[오류] 설정 파일 로드 실패. 기본값 세팅.");
            setFallbackConfig();
        }
    }

    private static void setFallbackConfig() {
        hwpExePath = "C:\\Program Files (x86)\\Hanscom\\HcomOffice11\\HOffice11\\Bin\\Hwp.exe";
        outputDir = "";
        timeoutSeconds = 30;
    }

    // 하위 클래스가 구현해야 할 핵심 추상 메서드
    public abstract boolean convertToPdf(String srcPath, String destPath);

    /**
     * 공통: 저장될 PDF 및 TXT의 최종 절대 경로 계산 (Properties 반영)
     */
    public static String getTargetDestPath(String srcPath, String extension) {
        File srcFile = new File(srcPath);
        String fileNameWithoutExt = srcFile.getName().substring(0, srcFile.getName().lastIndexOf('.'));

        if (outputDir != null && !outputDir.isEmpty()) {
            // properties에 정의된 폴더가 있으면 해당 폴더 사용
            return Paths.get(outputDir, fileNameWithoutExt + extension).toString();
        } else {
            // 정의된 폴더가 없으면 원본 파일과 동일한 폴더 사용
            return Paths.get(srcFile.getParent(), fileNameWithoutExt + extension).toString();
        }
    }

    /**
     * 공통: PDF에서 문자열을 추출하여 UTF-8 텍스트 파일로 저장
     */
    public static boolean extractTextFromPdf(String pdfPath, String txtPath) {
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) return false;

        try (PDDocument document = PDDocument.load(pdfFile);
             PrintWriter writer = new PrintWriter(Files.newBufferedWriter(Paths.get(txtPath), StandardCharsets.UTF_8))) {

            if (!document.isEncrypted()) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                String text = stripper.getText(document);
                writer.write(text);
                return true;
            } else {
                System.err.println("   ❌ 암호화된 PDF 파일이므로 텍스트를 추출할 수 없습니다: " + pdfPath);
                return false;
            }
        } catch (Exception e) {
            System.err.println("   ❌ PDF 텍스트 추출 중 예외 발생: " + e.getMessage());
            return false;
        }
    }

    /**
     * 공통: 에러 로그 파일 생성 (_ERR.txt)
     */
    public static void createErrorLog(String srcPath, String errorMessage) {
        String errLogPath = getTargetDestPath(srcPath, "_ERR.txt");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(Paths.get(errLogPath), StandardCharsets.UTF_8))) {
            writer.println("==================================================");
            writer.println("📄 변환 대상 파일: " + srcPath);
            writer.println("❌ 에러 발생 시각: " + new java.util.Date());
            writer.println("==================================================");
            writer.println(errorMessage);
        } catch (Exception e) {
            System.err.println("   ❌ 에러 로그 파일 생성 실패: " + e.getMessage());
        }
    }

    /**
     * 공통: CLI/PowerShell 외부 프로세스 실행 및 안전한 종료(타임아웃) 제어
     */
    protected static boolean executeProcess(String[] cmd) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (finished) {
                return process.exitValue() == 0;
            } else {
                System.err.println("   ❌ [타임아웃] 프로세스가 " + timeoutSeconds + "초 내에 끝나지 않아 강제 종료합니다.");
                process.destroyForcibly();
                return false;
            }
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return false;
        }
    }

    /**
     * 공통: PowerShell 명령어 실행기
     */
    protected static boolean executePowerShell(String command) {
        String[] cmd = { "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command };
        return executeProcess(cmd);
    }
}