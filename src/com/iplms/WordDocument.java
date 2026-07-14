package com.iplms;

import java.io.File;

// 1. Word 구현체
class WordDocument extends PDocument {
    @Override
    public boolean convertToPdf(String srcPath, String destPath) {
        String psCommand = String.format(
                "$word = New-Object -ComObject Word.Application; $word.Visible = $false; " +
                        "$doc = $word.Documents.Open('%s'); $doc.SaveAs([ref] '%s', [ref] 17); " +
                        "$doc.Close(); $word.Quit(); [System.Runtime.Interopservices.Marshal]::ReleaseComObject($word);",
                new File(srcPath).getAbsolutePath(), new File(destPath).getAbsolutePath()
        );
        return executePowerShell(psCommand);
    }
}

// 2. Excel 구현체
class ExcelDocument extends PDocument {
    @Override
    public boolean convertToPdf(String srcPath, String destPath) {
        String psCommand = String.format(
                "$excel = New-Object -ComObject Excel.Application; $excel.Visible = $false; " +
                        "$workbook = $excel.Workbooks.Open('%s'); $workbook.ExportAsFixedFormat(0, '%s'); " +
                        "$workbook.Close($false); $excel.Quit(); [System.Runtime.Interopservices.Marshal]::ReleaseComObject($excel);",
                new File(srcPath).getAbsolutePath(), new File(destPath).getAbsolutePath()
        );
        return executePowerShell(psCommand);
    }
}

// 3. PowerPoint 구현체
class PowerPointDocument extends PDocument {
    @Override
    public boolean convertToPdf(String srcPath, String destPath) {
        String psCommand = String.format(
                "$ppt = New-Object -ComObject PowerPoint.Application; " +
                        "$presentation = $ppt.Presentations.Open('%s', [Microsoft.Office.Core.MsoTriState]::msoTrue, [Microsoft.Office.Core.MsoTriState]::msoFalse, [Microsoft.Office.Core.MsoTriState]::msoFalse); " +
                        "$presentation.SaveAs('%s', 32); $presentation.Close(); $ppt.Quit(); [System.Runtime.Interopservices.Marshal]::ReleaseComObject($ppt);",
                new File(srcPath).getAbsolutePath(), new File(destPath).getAbsolutePath()
        );
        return executePowerShell(psCommand);
    }
}

// 4. 아래아한글 구현체
class HwpDocument extends PDocument {
    @Override
    public boolean convertToPdf(String srcPath, String destPath) {
        File hwpExe = new File(hwpExePath);
        if (!hwpExe.exists()) {
            System.err.println("   ❌ 한글 실행 파일이 경로에 없습니다: " + hwpExePath);
            return false;
        }
        String[] cmd = { hwpExePath, "/u", "/p", new File(srcPath).getAbsolutePath(), "/o", new File(destPath).getAbsolutePath() };
        return executeProcess(cmd);
    }
}