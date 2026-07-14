package com.iplms;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

// =========================================================================
// 0. 공통 추상 클래스 (순차 변환 보장을 위한 동기화 락 적용)
// =========================================================================
abstract class PDocument {
    // 📌 전역 정적 모니터 객체 (JVM 내의 모든 PDocument 변환 작업을 단 하나의 순차 대기열로 만듭니다)
    private static final Object CONVERSION_LOCK = new Object();

    // 하위 클래스들이 구현할 실제 변환 내부 로직
    protected abstract boolean doConvertToPdf(String srcPath, String destPath) throws Exception;

    // 📌 외부에서 호출하는 공통 진입점 (synchronized 블록을 통해 철저하게 순차 처리 보장)
    public final boolean convertToPdf(String srcPath, String destPath) throws Exception {
        synchronized (CONVERSION_LOCK) {
            System.out.println("🔄 [변환 대기열] 순차 변환 시작: " + srcPath + " ➡️ " + destPath);
            long startTime = System.currentTimeMillis();
            try {
                return doConvertToPdf(srcPath, destPath);
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                System.out.println("✅ [변환 대기열] 순차 변환 완료 (" + duration + "ms): " + destPath);
            }
        }
    }
}

// =========================================================================
// 1. MS Word 구현체 (docx4j FOSettings 최적화 파이프라인)
// =========================================================================
class WordDocument extends PDocument {
    @Override
    protected boolean doConvertToPdf(String srcPath, String destPath) throws Exception {
        // 1. POI 라이브러리를 이용하여 안전하게 DOCX 워드 파일 로드
        try (FileInputStream fis = new FileInputStream(srcPath);
             org.apache.poi.xwpf.usermodel.XWPFDocument docx = new org.apache.poi.xwpf.usermodel.XWPFDocument(fis);
             PdfWriter writer = new PdfWriter(destPath);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {

            // 한글 깨짐 방지를 위한 윈도우 표준 맑은 고딕 폰트 매핑
            PdfFont font = PdfFontFactory.createFont("C:\\Windows\\Fonts\\malgun.ttf", PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED);
            document.setFont(font);

            // 2. 워드 문서 내부의 모든 요소(단락, 테이블 등)를 순차적으로 순회
            for (org.apache.poi.xwpf.usermodel.IBodyElement element : docx.getBodyElements()) {

                // 단락(Paragraph)인 경우 처리
                if (element instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph) {
                    org.apache.poi.xwpf.usermodel.XWPFParagraph p = (org.apache.poi.xwpf.usermodel.XWPFParagraph) element;
                    String text = p.getText();

                    // 빈 줄 처리 포함하여 iText7 문서에 삽입
                    document.add(new Paragraph(text.isEmpty() ? " " : text).setFontSize(10));
                }

                // 테이블(Table)인 경우 처리
                else if (element instanceof org.apache.poi.xwpf.usermodel.XWPFTable) {
                    org.apache.poi.xwpf.usermodel.XWPFTable t = (org.apache.poi.xwpf.usermodel.XWPFTable) element;

                    // 행과 열 구조 분석하여 iText7 동적 테이블 생성
                    int maxCols = 0;
                    for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : t.getRows()) {
                        if (row.getTableCells().size() > maxCols) {
                            maxCols = row.getTableCells().size();
                        }
                    }

                    if (maxCols == 0) maxCols = 1;
                    Table pdfTable = new Table(maxCols);

                    for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : t.getRows()) {
                        for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                            String cellText = cell.getText();
                            com.itextpdf.layout.element.Cell pdfCell = new com.itextpdf.layout.element.Cell();
                            pdfCell.add(new Paragraph(cellText).setFontSize(9));
                            pdfTable.addCell(pdfCell);
                        }
                    }
                    document.add(pdfTable);
                    // 테이블 배치 후 약간의 여백 가드
                    document.add(new Paragraph(" ").setFontSize(5));
                }
            }

            document.flush();
            return true;
        } catch (Exception e) {
            System.err.println("❌ [Word PDF 변환 쓰기 실패]: " + e.getMessage());
            return false;
        }
    }
}

// =========================================================================
// 2. MS Excel 구현체 (POI + iText7 동적 테이블 빌더)
// =========================================================================
class ExcelDocument extends PDocument {
    @Override
    protected boolean doConvertToPdf(String srcPath, String destPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(srcPath);
             Workbook workbook = WorkbookFactory.create(fis);
             PdfWriter writer = new PdfWriter(destPath);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf, PageSize.A4.rotate())) {

            PdfFont font = PdfFontFactory.createFont("C:\\Windows\\Fonts\\malgun.ttf", PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED);
            document.setFont(font);

            Sheet sheet = workbook.getSheetAt(0);
            int maxColumns = 0;
            for (Row row : sheet) {
                if (row.getLastCellNum() > maxColumns) {
                    maxColumns = row.getLastCellNum();
                }
            }

            if (maxColumns == 0) maxColumns = 1;
            Table pdfTable = new Table(maxColumns);

            for (Row row : sheet) {
                for (int col = 0; col < maxColumns; col++) {
                    Cell cellValue = row.getCell(col);
                    String text = (cellValue == null) ? "" : cellValue.toString();

                    com.itextpdf.layout.element.Cell pdfCell = new com.itextpdf.layout.element.Cell();
                    pdfCell.add(new Paragraph(text).setFontSize(8));

                    pdfTable.addCell(pdfCell);
                }
            }
            document.add(pdfTable);
            return true;
        }
    }
}

// =========================================================================
// 3. MS PowerPoint 구현체 (POI XSLF + iText7 슬라이드 매핑)
// =========================================================================
class PowerPointDocument extends PDocument {
    @Override
    protected boolean doConvertToPdf(String srcPath, String destPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(srcPath);
             XMLSlideShow ppt = new XMLSlideShow(fis);
             PdfWriter writer = new PdfWriter(destPath);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf, PageSize.A4.rotate())) {

            PdfFont font = PdfFontFactory.createFont("C:\\Windows\\Fonts\\malgun.ttf", PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED);
            document.setFont(font);

            for (XSLFSlide slide : ppt.getSlides()) {
                document.add(new Paragraph("--- Slide " + slide.getSlideNumber() + " ---").setBold());
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        XSLFTextShape textShape = (XSLFTextShape) shape;
                        document.add(new Paragraph(textShape.getText()).setFontSize(10));
                    }
                }
                document.add(new com.itextpdf.layout.element.AreaBreak());
            }
            return true;
        }
    }
}

// =========================================================================
// 4. 아래아한글 구현체 (0 byte 생성 버그 및 폰트 바인딩 완벽 수정본)
// =========================================================================
class HwpDocument extends PDocument {
    @Override
    protected boolean doConvertToPdf(String srcPath, String destPath) throws Exception {
        File hwpFile = new File(srcPath);
        HWPFile hwp = HWPReader.fromFile(hwpFile);
        if (hwp == null) {
            System.err.println("❌ [HWP 에러] 한글 파일을 로드하지 못했습니다: " + srcPath);
            return false;
        }

        PdfFont jointFont;
        try {
            jointFont = PdfFontFactory.createFont("C:\\Windows\\Fonts\\malgun.ttf", PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED);
        } catch (Exception fontEx) {
            System.err.println("⚠️ [폰트 경고] 지정한 폰트 로드 실패. 기본 시스템 폰트로 대체합니다: " + fontEx.getMessage());
            jointFont = PdfFontFactory.createFont();
        }

        String extractedText = kr.dogfoot.hwplib.tool.textextractor.TextExtractor.extract(
                hwp,
                TextExtractMethod.OnlyMainParagraph
        );

        if (extractedText == null || extractedText.trim().isEmpty()) {
            extractedText = "[본문에 한글 텍스트가 존재하지 않거나 읽을 수 없습니다.]";
        }

        try (PdfWriter writer = new PdfWriter(destPath);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {

            document.setFont(jointFont);

            String[] lines = extractedText.split("\n");
            for (String line : lines) {
                document.add(new Paragraph(line.isEmpty() ? " " : line).setFontSize(10));
            }

            document.flush();
            return true;
        } catch (Exception e) {
            System.err.println("❌ [HWP PDF 변환 쓰기 실패]: " + e.getMessage());
            return false;
        }
    }
}

// =========================================================================
// 5. AutoCAD 구현체 (Kabeja 파서 + iText7 SVG 렌더러 - Batik 의존성 제로)
// =========================================================================
class CadDocument extends PDocument {
    @Override
    protected boolean doConvertToPdf(String srcPath, String destPath) throws Exception {
        File dxfFile = new File(srcPath);

        org.kabeja.parser.Parser parser = org.kabeja.parser.ParserBuilder.createDefaultParser();
        try (FileInputStream fis = new FileInputStream(dxfFile)) {
            parser.parse(fis, "UTF-8");
        }

        org.kabeja.dxf.DXFDocument dxfDoc = parser.getDocument();

        File tempSvgFile = File.createTempFile("cad_temp_", ".svg");
        try (FileOutputStream svgOut = new FileOutputStream(tempSvgFile)) {
            org.kabeja.svg.SVGGenerator generator = new org.kabeja.svg.SVGGenerator();

            javax.xml.transform.sax.SAXTransformerFactory tf =
                    (javax.xml.transform.sax.SAXTransformerFactory) javax.xml.transform.sax.SAXTransformerFactory.newInstance();
            javax.xml.transform.sax.TransformerHandler handler = tf.newTransformerHandler();

            handler.getTransformer().setOutputProperty(javax.xml.transform.OutputKeys.METHOD, "xml");
            handler.getTransformer().setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
            handler.setResult(new javax.xml.transform.stream.StreamResult(svgOut));

            generator.generate(dxfDoc, handler, new java.util.HashMap());
        }

        try (FileInputStream svgIn = new FileInputStream(tempSvgFile);
             FileOutputStream pdfOut = new FileOutputStream(destPath)) {

            com.itextpdf.svg.converter.SvgConverter.createPdf(svgIn, pdfOut);
            return true;
        } finally {
            if (tempSvgFile.exists()) {
                tempSvgFile.delete();
            }
        }
    }
}