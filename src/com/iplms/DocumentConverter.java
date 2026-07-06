package com.iplms;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Windows 서버 환경 전용 문서 변환 컴포넌트 (환경 검증 및 설정 파일 적용 버전)
 * MS Office(Word, Excel, PPT) 및 한컴오피스(HWP) -> PDF 변환 지원
 * * @author shpark
 */
public class DocumentConverter {

    private static String hwpExePath;
    private static long timeoutSeconds;

    // Static 초기화 블록에서 Properties 파일 로드
    static {
        Properties prop = new Properties();
        String propFileName = "config.properties"; // 클래스패스 루트 기준

        try (InputStream inputStream = DocumentConverter.class.getClassLoader().getResourceAsStream(propFileName)) {
            if (inputStream != null) {
                prop.load(inputStream);
                hwpExePath = prop.getProperty("converter.hwp.exe.path");
                timeoutSeconds = Long.parseLong(prop.getProperty("converter.timeout.seconds", "30"));
                System.out.println("[설정 로드 완료] HWP 경로: " + hwpExePath + ", 타임아웃: " + timeoutSeconds + "초");
            } else {
                System.err.println("[경고] 설정을 찾을 수 없습니다: " + propFileName + ". 기본값으로 세팅합니다.");
                setFallbackConfig();
            }
        } catch (Exception e) {
            System.err.println("[오류] 설정 파일을 읽는 중 예외가 발생했습니다. 기본값으로 세팅합니다.");
            e.printStackTrace();
            setFallbackConfig();
        }
    }

    private static void setFallbackConfig() {
        hwpExePath = "C:\\Program Files (x86)\\Hanscom\\HcomOffice11\\HOffice11\\Bin\\Hwp.exe";
        timeoutSeconds = 30;
    }

    /**
     * [신규] 실행 전 서버 환경 검증 메서드
     * HWP 경로 실재 여부 및 MS Office COM 객체 유효성을 체크합니다.
     */
    public static boolean checkEnvironment() {
        System.out.println("\n==================================================");
        System.out.println(" 🔍 서버 인프라 환경 검증 (Pre-flight Check)");
        System.out.println("==================================================");
        boolean isAllClear = true;

        // 1. 아래아한글 실행파일 검증
        File hwpExe = new File(hwpExePath);
        if (!hwpExe.exists()) {
            System.err.println("[환경오류] 한컴오피스 실행 파일이 존재하지 않습니다.");
            System.err.println("  ❌ 지정된 경로: " + hwpExePath);
            System.err.println("  💡 조치방법: config.properties의 'converter.hwp.exe.path' 설정을 확인하거나 한글을 설치하세요.\n");
            isAllClear = false;
        } else {
            System.out.println("  + 아래아한글 경로 검증 완료: OK");
        }

        // 2. MS Office COM Object 가용성 검증
        String[] apps = {"Word.Application", "Excel.Application", "PowerPoint.Application"};
        for (String app : apps) {
            // 가볍게 객체 생성 후 해제만 해보는 무상태 명령어
            String checkCmd = String.format(
                    "$obj = New-Object -ComObject %s; " +
                            "if($obj) { " +
                            "  [System.Runtime.Interopservices.Marshal]::ReleaseComObject($obj) | Out-Null; " +
                            "  Write-Output 'OK' " +
                            "} else { " +
                            "  Write-Output 'FAIL' " +
                            "}", app
            );

            if (!executeComCheckPowerShell(checkCmd)) {
                System.err.println("[환경오류] MS Office COM 컴포넌트 로드에 실패했습니다.");
                System.err.println("  ❌ 대상 객체: " + app);
                System.err.println("  💡 조치방법: 서버에 MS Office가 정상 설치 및 라이선스 인증이 되었는지, 혹은 실행 계정 권한이 충족하는지 확인하세요.\n");
                isAllClear = false;
            } else {
                System.out.println("  + MS Office [" + app + "] COM 객체 검증 완료: OK");
            }
        }

        System.out.println("==================================================\n");
        return isAllClear;
    }

    /**
     * COM 객체 존재 여부 판별을 위한 전용 PowerShell 실행기
     */
    private static boolean executeComCheckPowerShell(String command) {
        String[] cmd = { "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command };
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("OK".equals(line.trim())) {
                        return true;
                    }
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 예외 발생 시 구동 실패로 판단
        }
        return false;
    }

    /**
     * MS Word (.docx, .doc) -> PDF 변환
     */
    public synchronized static boolean convertWordToPdf(String srcPath, String destPath) {
        String absoluteSrc = new File(srcPath).getAbsolutePath();
        String absoluteDest = new File(destPath).getAbsolutePath();

        String psCommand = String.format(
                "$word = New-Object -ComObject Word.Application; " +
                        "$word.Visible = $false; " +
                        "$doc = $word.Documents.Open('%s'); " +
                        "$doc.SaveAs([ref] '%s', [ref] 17); " + // 17: wdFormatPDF
                        "$doc.Close(); " +
                        "$word.Quit(); " +
                        "[System.Runtime.Interopservices.Marshal]::ReleaseComObject($word);",
                absoluteSrc, absoluteDest
        );

        return executePowerShell(psCommand);
    }

    /**
     * MS Excel (.xlsx, .xls) -> PDF 변환
     */
    public synchronized static boolean convertExcelToPdf(String srcPath, String destPath) {
        String absoluteSrc = new File(srcPath).getAbsolutePath();
        String absoluteDest = new File(destPath).getAbsolutePath();

        String psCommand = String.format(
                "$excel = New-Object -ComObject Excel.Application; " +
                        "$excel.Visible = $false; " +
                        "$workbook = $excel.Workbooks.Open('%s'); " +
                        "$workbook.ExportAsFixedFormat(0, '%s'); " + // 0: xlTypePDF
                        "$workbook.Close($false); " +
                        "$excel.Quit(); " +
                        "[System.Runtime.Interopservices.Marshal]::ReleaseComObject($excel);",
                absoluteSrc, absoluteDest
        );

        return executePowerShell(psCommand);
    }

    /**
     * MS PowerPoint (.pptx, .ppt) -> PDF 변환
     */
    public synchronized static boolean convertPowerPointToPdf(String srcPath, String destPath) {
        String absoluteSrc = new File(srcPath).getAbsolutePath();
        String absoluteDest = new File(destPath).getAbsolutePath();

        String psCommand = String.format(
                "$ppt = New-Object -ComObject PowerPoint.Application; " +
                        "$presentation = $ppt.Presentations.Open('%s', [Microsoft.Office.Core.MsoTriState]::msoTrue, [Microsoft.Office.Core.MsoTriState]::msoFalse, [Microsoft.Office.Core.MsoTriState]::msoFalse); " +
                        "$presentation.SaveAs('%s', 32); " + // 32: ppSaveAsPDF
                        "$presentation.Close(); " +
                        "$ppt.Quit(); " +
                        "[System.Runtime.Interopservices.Marshal]::ReleaseComObject($ppt);",
                absoluteSrc, absoluteDest
        );

        return executePowerShell(psCommand);
    }

    /**
     * 아래아한글 (.hwp, .hwpx) -> PDF 변환
     */
    public synchronized static boolean convertHwpToPdf(String srcPath, String destPath) {
        String absoluteSrc = new File(srcPath).getAbsolutePath();
        String absoluteDest = new File(destPath).getAbsolutePath();

        File hwpExe = new File(hwpExePath);
        if (!hwpExe.exists()) {
            System.err.println("[오류] 설정된 경로에 한글 실행파일이 존재하지 않습니다: " + hwpExePath);
            return false;
        }

        String[] cmd = { hwpExePath, "/u", "/p", absoluteSrc, "/o", absoluteDest };
        return executeProcess(cmd);
    }

    private static boolean executePowerShell(String command) {
        String[] cmd = { "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command };
        return executeProcess(cmd);
    }

    private static boolean executeProcess(String[] cmd) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (finished) {
                int exitCode = process.exitValue();
                return exitCode == 0;
            } else {
                System.err.println("[오류] 변환 프로세스 타임아웃 발생 (" + timeoutSeconds + "초 초과).");
                process.destroyForcibly();
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (process != null) process.destroyForcibly();
            return false;
        }
    }

    // =========================================================================
    // MAIN METHOD (실행부)
    // =========================================================================
    public static void main(String[] args) {

        // 🚀 [필수 단계] 환경 검증 수행
        if (!DocumentConverter.checkEnvironment()) {
            System.err.println("❌ [시스템 중단] 서버 인프라 환경 검증을 통과하지 못해 프로그램을 종료합니다.");
            System.exit(1); // 에러 코드를 들고 프로세스 즉시 종료
        }

        String testDir = "D:\\_SHP\\temp\\officeconverter\\";

        System.out.println("==================================================");
        System.out.println(" ▶ 문서 PDF 통합 변환 테스트 시작");
        System.out.println("==================================================");

        // 1. MS Word 변환 테스트
        String wordSrc = testDir + "샘플_계약서.docx";
        String wordDst = testDir + "결과_계약서.pdf";
        runTest("MS Word (.docx)", wordSrc, wordDst, () -> DocumentConverter.convertWordToPdf(wordSrc, wordDst));

        // 2. MS Excel 변환 테스트
        String excelSrc = testDir + "샘플_정산서.xlsx";
        String excelDst = testDir + "결과_정산서.pdf";
        runTest("MS Excel (.xlsx)", excelSrc, excelDst, () -> DocumentConverter.convertExcelToPdf(excelSrc, excelDst));

        // 3. MS PowerPoint 변환 테스트
        String pptSrc = testDir + "샘플_제안서.pptx";
        String pptDst = testDir + "결과_제안서.pdf";
        runTest("MS PPT (.pptx)", pptSrc, pptDst, () -> DocumentConverter.convertPowerPointToPdf(pptSrc, pptDst));

        // 4. 아래아한글 변환 테스트
        String hwpSrc = testDir + "샘플_보고서.hwp";
        String hwpDst = testDir + "결과_보고서.pdf";
        runTest("한컴오피스 (.hwp)", hwpSrc, hwpDst, () -> DocumentConverter.convertHwpToPdf(hwpSrc, hwpDst));

        System.out.println("==================================================");
        System.out.println(" ▶ 모든 테스트 프로세스 종료");
        System.out.println("==================================================");
    }

    private static void runTest(String formatName, String src, String dst, java.util.concurrent.Callable<Boolean> conversionTask) {
        System.out.println(String.format("[%s] 변환 시도...", formatName));

        File srcFile = new File(src);
        if (!srcFile.exists()) {
            System.err.println(String.format("   ❌ [중단] 원본 파일이 존재하지 않습니다: %s\n", src));
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            boolean isSuccess = conversionTask.call();
            long duration = System.currentTimeMillis() - startTime;

            if (isSuccess) {
                File dstFile = new File(dst);
                if (dstFile.exists()) {
                    System.out.println(String.format("   ✅ [성공] 변환 완료 (%d ms)", duration));
                    System.out.println(String.format("   └── 📄 생성 경로: %s\n", dst));
                } else {
                    System.err.println("   ❌ [실패] 메서드는 true를 반환했으나 실제 PDF 파일이 생성되지 않았습니다.\n");
                }
            } else {
                System.err.println("   ❌ [실패] 변환 도중 에러 또는 타임아웃이 발생했습니다.\n");
            }
        } catch (Exception e) {
            System.err.println("   ❌ [오류] 테스트 실행 중 런타임 예외 발생\n");
            e.printStackTrace();
        }
    }
}