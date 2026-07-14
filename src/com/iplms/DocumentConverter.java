package com.iplms;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PDF 변환 및 문자열 추출 통합 실행 마스터 엔지니어 클래스
 * @author shpark
 */
public class DocumentConverter extends PDocument {

    @Override
    public boolean convertToPdf(String srcPath, String destPath) {
        // 마스터는 하위 팩토리 라우팅만 담당하므로 구현 패스
        return false;
    }

    /**
     * 🚀 [Pre-flight Check] 인프라 사전 환경 검증
     */
    public static boolean checkEnvironment() {
        System.out.println("\n==================================================");
        System.out.println(" 🔍 서버 인프라 환경 검증 (Pre-flight Check)");
        System.out.println("==================================================");
        boolean isAllClear = true;

        // 1. HWP 존재 검증
        File hwpExe = new File(hwpExePath);
        if (!hwpExe.exists()) {
            System.err.println(" ❌ [환경오류] 한컴오피스 실행 파일 유실: " + hwpExePath);
            isAllClear = false;
        } else {
            System.out.println("  + 아래아한글 환경 검증 완료: OK");
        }

        // 2. MS Office COM 객체 검증
        String[] apps = {"Word.Application", "Excel.Application", "PowerPoint.Application"};
        for (String app : apps) {
            String checkCmd = String.format(
                    "$obj = New-Object -ComObject %s; " +
                            "if($obj) { [System.Runtime.Interopservices.Marshal]::ReleaseComObject($obj) | Out-Null; Write-Output 'OK' } else { Write-Output 'FAIL' }", app
            );

            boolean comOk = false;
            try {
                String[] cmd = { "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", checkCmd };
                Process p = Runtime.getRuntime().exec(cmd);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if ("OK".equals(line.trim())) comOk = true;
                    }
                }
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                comOk = false;
            }

            if (!comOk) {
                System.err.println(" ❌ [환경오류] MS Office COM 컴포넌트 로드 실패: " + app);
                isAllClear = false;
            } else {
                System.out.println("  + MS Office [" + app + "] COM 검증 완료: OK");
            }
        }
        System.out.println("==================================================\n");
        return isAllClear;
    }

    /**
     * 🔒 [Core] 동시성 충돌 방지를 위한 동기화 기반 팩토리 라우팅 변환 처리
     */
    public synchronized static void processConversion(String srcPath) {
        File srcFile = new File(srcPath);
        System.out.println("▶ [작업 시작] 대상 파일: " + srcFile.getName());

        if (!srcFile.exists()) {
            System.err.println("   ❌ [중단] 원본 파일이 존재하지 않습니다.");
            return;
        }

        String ext = srcPath.substring(srcPath.lastIndexOf('.')).toLowerCase();
        PDocument targetEngine = null;

        // 확장자별 하위 엔진 매핑 (팩토리 라우팅)
        switch (ext) {
            case ".docx": case ".doc":
                targetEngine = new WordDocument(); break;
            case ".xlsx": case ".xls":
                targetEngine = new ExcelDocument(); break;
            case ".pptx": case ".ppt":
                targetEngine = new PowerPointDocument(); break;
            case ".hwp":  case ".hwpx":
                targetEngine = new HwpDocument(); break;
            default:
                System.err.println("   ❌ [지원하지 않는 확장자] 작업을 건너뜁니다: " + ext);
                createErrorLog(srcPath, "지원하지 않는 확장자 포맷: " + ext);
                return;
        }

        // 목적지 경로 연산
        String pdfDst = getTargetDestPath(srcPath, ".pdf");
        String txtDst = getTargetDestPath(srcPath, ".txt");

        // [Skip Policy] 기존 파일 존재 여부 확인
        if (new File(pdfDst).exists() && new File(txtDst).exists()) {
            System.out.println("   ⚠️ [SKIP] 이미 변환된 PDF 및 TXT 파일이 존재하여 스킵합니다.\n");
            return;
        }

        try {
            // 1단계: PDF 변환
            System.out.println("   └ 1단계: PDF 변환 중...");
            boolean convertSuccess = targetEngine.convertToPdf(srcPath, pdfDst);

            if (convertSuccess && new File(pdfDst).exists()) {
                System.out.println("   └ 1단계 성공: PDF 생성 완료.");

                // 2단계: 텍스트 추출
                System.out.println("   └ 2단계: 텍스트 추출 중...");
                boolean textSuccess = extractTextFromPdf(pdfDst, txtDst);
                if (textSuccess) {
                    System.out.println("   ✅ [작업 완료] PDF 및 TXT 추출 성공.\n");
                } else {
                    System.err.println("   ❌ [2단계 실패] 텍스트 추출 중 오류가 발생했습니다.\n");
                    createErrorLog(srcPath, "PDF 생성은 성공했으나, 텍스트 스트리핑(추출)에 실패했습니다.");
                }
            } else {
                System.err.println("   ❌ [1단계 실패] PDF 변환 실패 또는 타임아웃 발생.\n");
                createErrorLog(srcPath, "오피스 엔진을 통한 PDF 변환 컴포넌트 구동 실패 (타임아웃 또는 파일 오픈 에러)");
            }
        } catch (Exception e) {
            System.err.println("   ❌ [런타임 에러] 처리 중 예외 발생.\n");
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            createErrorLog(srcPath, sw.toString());
        }
    }

    /**
     * 🚀 애플리케이션 시작점 (Main)
     */
    public static void main(String[] args) {
        // 1. 아규먼트 정합성 검성
        if (args.length < 1) {
            System.err.println("[오류] 변환 대상 파일 목록(TXT) 경로가 지정되지 않았습니다.");
            System.err.println("Usage: java -jar iplms-converter.jar [목록파일경로.txt]");
            System.exit(1);
        }

        String queueFilePath = args[0];

        // 2. 서버 인프라 환경 검증 실행
        if (!checkEnvironment()) {
            System.err.println("❌ [시스템 종료] 서버 환경 인프라 요구조건을 충족하지 못해 구동을 중단합니다.");
            System.exit(1);
        }

        // 3. 큐 리스트 읽기 및 실행 순회
        try {
            File queueFile = new File(queueFilePath);
            if (!queueFile.exists()) {
                System.err.println("❌ [오류] 입력 큐 텍스트 파일이 존재하지 않습니다: " + queueFilePath);
                System.exit(1);
            }

            System.out.println("📥 [큐 로드] 대상 리스트 파일을 읽는 중: " + queueFile.getName());
            // UTF-8 표준 인코딩으로 줄바꿈 기준 모든 파일 경로 로드
            List<String> targetLines = Files.readAllLines(Paths.get(queueFilePath), StandardCharsets.UTF_8);

            System.out.println("📝 총 [" + targetLines.size() + "]개의 태스크를 순차 처리합니다.");
            System.out.println("==================================================");

            for (String targetPath : targetLines) {
                if (targetPath == null || targetPath.trim().isEmpty()) continue;
                // 각 파일별 순차 동기화 변환 실행
                processConversion(targetPath.trim());
            }

            System.out.println("==================================================");
            System.out.println("🎉 [전체 프로세스 완료] 모든 큐 파일 처리가 종료되었습니다.");

        } catch (Exception e) {
            System.err.println("❌ [치명적 크래시] 목록 파일을 처리하는 중 예외 발생");
            e.printStackTrace();
        }
    }
}