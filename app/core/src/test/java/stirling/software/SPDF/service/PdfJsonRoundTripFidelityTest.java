package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.model.json.PdfJsonDocument;
import stirling.software.SPDF.model.json.PdfJsonDocumentMetadata;
import stirling.software.SPDF.model.json.PdfJsonPage;
import stirling.software.SPDF.model.json.PdfJsonTextElement;
import stirling.software.SPDF.service.pdfjson.PdfJsonFontService;
import stirling.software.SPDF.service.pdfjson.type3.Type3FontConversionService;
import stirling.software.SPDF.service.pdfjson.type3.Type3GlyphExtractor;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.TaskManager;
import stirling.software.common.util.TempFileManager;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Visual-fidelity harness for the PDF text editor round trip.
 *
 * <p>The sibling {@code PdfJsonConversionServiceRoundTripTest} proves the JSON model preserves
 * structure. This one proves the rebuilt PDF still <em>looks</em> like the original: each fixture
 * is rendered before and after the PDF -&gt; JSON -&gt; PDF trip and compared pixel by pixel.
 *
 * <p>Thresholds encode measured behaviour today, not aspiration. When a fidelity fix lands, raise
 * the matching floor so the improvement cannot silently regress. Rendered PNGs (original, rebuilt,
 * and a red diff mask) are written to {@code build/fidelity-reports/&lt;fixture&gt;/} on every run
 * so a failure can be eyeballed instead of guessed at.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PDF -> JSON -> PDF visual fidelity")
class PdfJsonRoundTripFidelityTest {

    /** Render DPI. 100 is high enough to catch real drift, low enough to stay fast. */
    private static final float RENDER_DPI = 100f;

    /** Per-channel tolerance absorbing antialiasing noise between two renders. */
    private static final int CHANNEL_TOLERANCE = 16;

    /** Channel value below which a pixel counts as content rather than blank page. */
    private static final int INK_THRESHOLD = 250;

    private static final Path REPORT_DIR = Paths.get("build", "fidelity-reports");

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private EndpointConfiguration endpointConfiguration;
    @Mock private TempFileManager tempFileManager;
    @Mock private TaskManager taskManager;

    // Font handling is deliberately NOT mocked. Stubbing it out makes the rebuild emit no glyphs
    // at all, so every page renders blank and the comparison silently scores a blank page against
    // a blank page. A fidelity harness has to run the real font pipeline or it measures nothing.
    private final ApplicationProperties applicationProperties = new ApplicationProperties();
    private final Type3GlyphExtractor type3GlyphExtractor = new Type3GlyphExtractor();
    private final Type3FontConversionService type3FontConversionService =
            new Type3FontConversionService(List.of(), type3GlyphExtractor);

    private PdfJsonFallbackFontService fallbackFontService;
    private PdfJsonFontService fontService;

    private final PdfJsonCosMapper cosMapper = new PdfJsonCosMapper();

    private final ObjectMapper objectMapper =
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build();

    private PdfJsonConversionService service;

    private final List<Path> createdTempFiles = new ArrayList<>();

    private int jobCounter;

    @BeforeEach
    void setUp() throws IOException {
        fallbackFontService =
                new PdfJsonFallbackFontService(new DefaultResourceLoader(), applicationProperties);
        // The location normally arrives via @Value + @PostConstruct, neither of which runs when the
        // bean is constructed directly, leaving it null and failing every resource lookup.
        ReflectionTestUtils.setField(
                fallbackFontService,
                "fallbackFontLocation",
                PdfJsonFallbackFontService.DEFAULT_FALLBACK_FONT_LOCATION);
        fontService = new PdfJsonFontService(tempFileManager, applicationProperties);

        service =
                new PdfJsonConversionService(
                        pdfDocumentFactory,
                        objectMapper,
                        endpointConfiguration,
                        tempFileManager,
                        taskManager,
                        cosMapper,
                        fallbackFontService,
                        fontService,
                        type3FontConversionService,
                        type3GlyphExtractor,
                        applicationProperties);

        when(tempFileManager.createTempFile(anyString()))
                .thenAnswer(
                        invocation -> {
                            Path path =
                                    Files.createTempFile(
                                            "pdfjson-fidelity", invocation.getArgument(0));
                            createdTempFiles.add(path);
                            return path.toFile();
                        });
        when(tempFileManager.deleteTempFile(any(File.class)))
                .thenAnswer(
                        invocation -> {
                            File file = invocation.getArgument(0);
                            return file != null && file.delete();
                        });
        when(pdfDocumentFactory.load(any(Path.class), eq(true)))
                .thenAnswer(
                        invocation ->
                                Loader.loadPDF(invocation.getArgument(0, Path.class).toFile()));
        when(pdfDocumentFactory.load(any(byte[].class), eq(true)))
                .thenAnswer(invocation -> Loader.loadPDF(invocation.getArgument(0, byte[].class)));
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path path : createdTempFiles) {
            Files.deleteIfExists(path);
        }
        createdTempFiles.clear();
    }

    // ------------------------------------------------------------------
    // The harness
    // ------------------------------------------------------------------

    static Stream<Arguments> fixtures() {
        // Every fixture currently round-trips pixel-identically outside the edited run. The floor
        // sits just below that so antialiasing jitter cannot fail the build, while any real
        // regression - a lost colour, a substituted font, a shifted baseline - drops well through
        // it. Raise a floor when a fix improves it; never lower one to make a failure go away.
        return Stream.of(
                Arguments.of(
                        "plain-text",
                        (PdfSupplier) PdfJsonRoundTripFidelityTest::plainTextPdf,
                        0.99),
                Arguments.of(
                        "rotated-text",
                        (PdfSupplier) PdfJsonRoundTripFidelityTest::rotatedTextPdf,
                        0.99),
                Arguments.of(
                        "cmyk-colour",
                        (PdfSupplier) PdfJsonRoundTripFidelityTest::cmykColourPdf,
                        0.99),
                Arguments.of(
                        "multi-column",
                        (PdfSupplier) PdfJsonRoundTripFidelityTest::multiColumnPdf,
                        0.99),
                Arguments.of(
                        "transparency",
                        (PdfSupplier) PdfJsonRoundTripFidelityTest::transparencyPdf,
                        0.99),
                Arguments.of(
                        "mixed-sizes",
                        (PdfSupplier) PdfJsonRoundTripFidelityTest::mixedSizesPdf,
                        0.99),

                // Same content, but with an image on the page so the export is forced to rebuild
                // the whole page from the JSON model instead of patching text operators in place.
                // These are the ones that expose real fidelity loss.
                Arguments.of(
                        "plain-text+image", (PdfSupplier) () -> withImage(plainTextPdf()), 0.99),
                Arguments.of(
                        "rotated-text+image",
                        (PdfSupplier) () -> withImage(rotatedTextPdf()),
                        0.99),
                Arguments.of(
                        "cmyk-colour+image", (PdfSupplier) () -> withImage(cmykColourPdf()), 0.99),
                Arguments.of(
                        "multi-column+image",
                        (PdfSupplier) () -> withImage(multiColumnPdf()),
                        0.99),
                Arguments.of(
                        "transparency+image",
                        (PdfSupplier) () -> withImage(transparencyPdf()),
                        0.99),
                Arguments.of(
                        "mixed-sizes+image", (PdfSupplier) () -> withImage(mixedSizesPdf()), 0.99));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void roundTripPreservesAppearance(String name, PdfSupplier builder, double minSimilarity)
            throws IOException {
        byte[] original = builder.get();
        Rebuild rebuild = roundTrip(name, original);

        BufferedImage before = renderFirstPage(original);
        BufferedImage after = renderFirstPage(rebuild.pdf());

        Comparison comparison = compareAndReport(name, before, after, rebuild.excluded());
        double similarity = comparison.similarity();

        // A perfect score is only meaningful if the page was actually rebuilt. If the exporter took
        // the surgical rewrite path the bytes come back near-identical and 1.0 proves nothing, so
        // report the content-stream sizes and let the numbers say which path ran.
        int originalStream = contentStreamLength(original);
        int rebuiltStream = contentStreamLength(rebuild.pdf());

        Files.write(REPORT_DIR.resolve(name).resolve("rebuilt.pdf"), rebuild.pdf());

        System.out.printf(
                "[fidelity] %-22s similarity = %.4f over %5d ink px  stream %d -> %d (%s)  fonts:"
                        + " %s -> %s%n",
                name,
                similarity,
                comparison.inkPixels(),
                originalStream,
                rebuiltStream,
                originalStream == rebuiltStream ? "UNCHANGED - fast path" : "REBUILT",
                fontNames(original),
                fontNames(rebuild.pdf()));

        // A similarity of 1.0 over nothing is the failure mode this harness keeps walking into: an
        // over-broad exclusion, a blank render, or a short-circuited export all produce a perfect
        // score while measuring no pixels at all. Require real content to have been compared.
        assertTrue(
                comparison.inkPixels() > 500,
                () ->
                        String.format(
                                "Fixture '%s' only scored %d ink pixels - the comparison is not"
                                        + " measuring the page, so its score is meaningless.",
                                name, comparison.inkPixels()));

        assertTrue(
                similarity >= minSimilarity,
                () ->
                        String.format(
                                "Fixture '%s' fidelity dropped to %.4f (floor %.4f). See %s",
                                name, similarity, minSimilarity, REPORT_DIR.resolve(name)));
    }

    /** A rebuilt PDF plus the pixel rows covering the edited line, which scoring must ignore. */
    private record Rebuild(byte[] pdf, ExcludedRun excluded) {}

    /**
     * The edited run, expressed in its own frame: an origin, a baseline direction, and extents
     * along and perpendicular to it.
     *
     * <p>An axis-aligned box cannot express this. It has to reach far enough along the baseline to
     * cover however much wider the replacement text renders, and for a diagonal run that reach
     * turns the bounding box into most of the page - which would silently exclude everything and
     * report a perfect score. Projecting each pixel onto the run's own axes keeps the excluded
     * region a tight sliver no matter how the run is rotated.
     */
    private record ExcludedRun(
            float originX,
            float originY,
            float advanceX,
            float advanceY,
            float upX,
            float upY,
            float alongMin,
            float alongMax,
            float upMin,
            float upMax,
            float pageHeight) {

        static ExcludedRun none() {
            return new ExcludedRun(0, 0, 1, 0, 0, 1, 1, -1, 1, -1, 792);
        }

        boolean contains(int col, int row) {
            float scale = RENDER_DPI / 72f;
            float x = col / scale;
            float y = pageHeight - row / scale;
            float dx = x - originX;
            float dy = y - originY;
            float along = dx * advanceX + dy * advanceY;
            float up = dx * upX + dy * upY;
            return along >= alongMin && along <= alongMax && up >= upMin && up <= upMax;
        }
    }

    /**
     * Drives the real editor save path - cache the document, pull the page model, edit one line,
     * export - and reports which rows the edit occupies so scoring can ignore them.
     *
     * <p>The edit is essential. Resubmitting a page unchanged lets {@code rewriteTextOperators}
     * patch the operators in place and hand back byte-identical content, which scores a meaningless
     * 1.0. Changing the text is what defeats the surgical path and forces the page to be rebuilt
     * from the JSON model - the path where colour, transparency and layering are actually at risk,
     * and the one every real edit takes.
     *
     * <p>Everything outside the edited line must survive untouched: editing one line is not licence
     * to move the rest of the page.
     */
    /**
     * Measures real documents instead of constructed ones.
     *
     * <p>Every other fixture here is synthetic, which makes them easy to reason about and easy to
     * flatter: they use one standard-14 font, simple layout and no subsetting. A brochure or an
     * annual report is a harder test than anything this file builds. Drop PDFs into {@code
     * app/core/build/fidelity-input/} and this reports the same ink-restricted score for page 1 of
     * each, writing the usual before/after/diff PNGs alongside.
     *
     * <p>Skipped when that directory is empty, so it costs nothing in CI.
     */
    @Test
    @DisplayName("real documents, when any are supplied")
    void realDocumentsRoundTrip() throws IOException {
        Path inputDir = Paths.get("build", "fidelity-input");
        List<Path> documents =
                Files.exists(inputDir)
                        ? Files.list(inputDir)
                                .filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                                .sorted()
                                .toList()
                        : List.of();
        assumeTrue(!documents.isEmpty(), "no PDFs in build/fidelity-input");

        for (Path document : documents) {
            String name =
                    "real-" + document.getFileName().toString().replaceAll("[^A-Za-z0-9.-]", "_");
            byte[] original = Files.readAllBytes(document);
            try {
                Rebuild rebuild = roundTrip(name, original);
                BufferedImage before = renderFirstPage(original);
                BufferedImage after = renderFirstPage(rebuild.pdf());
                Comparison comparison = compareAndReport(name, before, after, rebuild.excluded());
                Files.write(REPORT_DIR.resolve(name).resolve("rebuilt.pdf"), rebuild.pdf());
                Files.write(
                        REPORT_DIR.resolve(name).resolve("page1-content.txt"),
                        decodedPageOneContent(rebuild.pdf()));
                System.out.printf(
                        "[fidelity-real] %-46s similarity = %.4f over %6d ink px  stream %d -> %d%n",
                        document.getFileName(),
                        comparison.similarity(),
                        comparison.inkPixels(),
                        contentStreamLength(original),
                        contentStreamLength(rebuild.pdf()));
            } catch (Exception e) {
                // Report rather than abort: one unusable document should not hide the others.
                System.out.printf(
                        "[fidelity-real] %-46s FAILED %s: %s%n",
                        document.getFileName(), e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private Rebuild roundTrip(String fixtureName, byte[] pdfBytes) throws IOException {
        String jobId = "fidelity-job-" + (++jobCounter);

        ByteArrayOutputStream metadataOut = new ByteArrayOutputStream();
        service.extractDocumentMetadata(
                new MockMultipartFile("fileInput", "input.pdf", "application/pdf", pdfBytes),
                jobId,
                metadataOut);
        PdfJsonDocumentMetadata metadata =
                objectMapper.readValue(metadataOut.toByteArray(), PdfJsonDocumentMetadata.class);

        // extractSinglePage writes a bare PdfJsonPage, not a PdfJsonDocument. Deserialising it as a
        // document leaves pages null, and exportUpdatedPages then short-circuits to "no page
        // updates" and returns the cached original - which looks like a perfect round trip.
        ByteArrayOutputStream pageOut = new ByteArrayOutputStream();
        service.extractSinglePage(jobId, 1, pageOut);
        Files.createDirectories(REPORT_DIR.resolve(fixtureName));
        Files.write(REPORT_DIR.resolve(fixtureName).resolve("page.json"), pageOut.toByteArray());
        PdfJsonPage pageModel = objectMapper.readValue(pageOut.toByteArray(), PdfJsonPage.class);

        PdfJsonDocument updates = new PdfJsonDocument();
        updates.setPages(new ArrayList<>(List.of(pageModel)));
        // The editor sends the font models back with the export; without them the rebuild has to
        // guess, so omitting them here would blame the exporter for the harness's own omission.
        updates.setFonts(metadata.getFonts());
        ExcludedRun excluded = editLastTextElement(updates);

        ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
        service.exportUpdatedPages(jobId, updates, pdfOut);
        return new Rebuild(pdfOut.toByteArray(), excluded);
    }

    /**
     * Rewrites the final text element in place and returns the pixel box it occupies.
     *
     * <p>The replacement keeps the original character count, so the edited run covers the same
     * footprint as before and the excluded region stays small and well defined. Appending text
     * instead would push the run past any box that can be derived from the original geometry, and
     * the overflow would then be scored as if the rebuild had damaged the page.
     *
     * <p>The box is built from the text matrix rather than from a horizontal band, because a
     * rotated run's rows span most of the page - a band wide enough to contain it would swallow
     * unrelated content and hide real regressions.
     */
    private static ExcludedRun editLastTextElement(PdfJsonDocument document) {
        List<PdfJsonPage> pages = document.getPages();
        if (pages == null || pages.isEmpty()) {
            return ExcludedRun.none();
        }
        PdfJsonPage page = pages.get(0);
        List<PdfJsonTextElement> elements = page.getTextElements();
        if (elements == null || elements.isEmpty()) {
            return ExcludedRun.none();
        }

        PdfJsonTextElement target = elements.get(elements.size() - 1);
        String original = Objects.toString(target.getText(), "");
        if (original.isEmpty()) {
            return ExcludedRun.none();
        }
        target.setText("X".repeat(original.length()));

        float pageHeight = page.getHeight() != null ? page.getHeight() : 792f;
        float width = target.getWidth() != null ? target.getWidth() : 0f;
        float height = target.getHeight() != null ? target.getHeight() : 12f;

        float[] matrix = target.getTextMatrix();
        float originX;
        float originY;
        float advanceX;
        float advanceY;
        float upX;
        float upY;
        if (matrix != null && matrix.length >= 6) {
            originX = matrix[4];
            originY = matrix[5];
            float advanceLength = (float) Math.hypot(matrix[0], matrix[1]);
            float upLength = (float) Math.hypot(matrix[2], matrix[3]);
            advanceX = advanceLength == 0 ? 1f : matrix[0] / advanceLength;
            advanceY = advanceLength == 0 ? 0f : matrix[1] / advanceLength;
            upX = upLength == 0 ? 0f : matrix[2] / upLength;
            upY = upLength == 0 ? 1f : matrix[3] / upLength;
        } else {
            originX = target.getX() != null ? target.getX() : 0f;
            originY = target.getY() != null ? target.getY() : 0f;
            advanceX = 1f;
            advanceY = 0f;
            upX = 0f;
            upY = 1f;
        }

        // Reach well past the original width along the baseline: the replacement glyphs may render
        // wider, and that overflow is the edit's own footprint, not damage. Perpendicular extent
        // stays tight so neighbouring lines are still scored.
        float padding = 6f;
        return new ExcludedRun(
                originX,
                originY,
                advanceX,
                advanceY,
                upX,
                upY,
                -padding,
                Math.max(width, 0f) + 400f,
                -height * 0.5f - padding,
                height + padding,
                pageHeight);
    }

    /** Base names of the fonts page 1 actually references, to prove which font the rebuild used. */
    private static String fontNames(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDResources resources = document.getPage(0).getResources();
            if (resources == null) {
                return "<none>";
            }
            List<String> names = new ArrayList<>();
            for (COSName name : resources.getFontNames()) {
                try {
                    PDFont font = resources.getFont(name);
                    names.add(font == null ? name.getName() + "=?" : font.getName());
                } catch (IOException ex) {
                    names.add(name.getName() + "=<unreadable>");
                }
            }
            return String.join(",", names);
        }
    }

    /** Page 1's decoded content stream, for inspecting what the rebuild actually emitted. */
    private static byte[] decodedPageOneContent(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Iterator<PDStream> streams = document.getPage(0).getContentStreams();
            while (streams.hasNext()) {
                out.write(streams.next().toByteArray());
            }
            return out.toByteArray();
        }
    }

    /** Decoded byte length of page 1's content streams - a cheap proxy for "was this rebuilt". */
    private static int contentStreamLength(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int total = 0;
            Iterator<PDStream> streams = document.getPage(0).getContentStreams();
            while (streams.hasNext()) {
                total += streams.next().toByteArray().length;
            }
            return total;
        }
    }

    private static BufferedImage renderFirstPage(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return new PDFRenderer(document).renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
        }
    }

    /**
     * Returns the fraction of <em>ink</em> pixels matching within {@link #CHANNEL_TOLERANCE}, and
     * writes the before/after/diff PNGs for inspection. A size mismatch scores 0 rather than
     * throwing, so a page-geometry regression surfaces as a number instead of an exception.
     *
     * <p>Scoring is deliberately restricted to pixels carrying content in either render. A
     * whole-page ratio is dominated by blank margins - a letter page of body text is roughly 95%
     * white, so text could land in completely the wrong place and still score above 0.9. Measuring
     * over the union of non-background pixels makes the number sensitive to the thing under test.
     */
    private record Comparison(double similarity, long inkPixels) {}

    private static Comparison compareAndReport(
            String name, BufferedImage before, BufferedImage after, ExcludedRun excluded)
            throws IOException {
        Path dir = REPORT_DIR.resolve(name);
        Files.createDirectories(dir);
        ImageIO.write(before, "png", dir.resolve("original.png").toFile());
        ImageIO.write(after, "png", dir.resolve("rebuilt.png").toFile());

        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
            return new Comparison(0.0, 0);
        }

        int width = before.getWidth();
        int height = before.getHeight();
        BufferedImage diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        long inkPixels = 0;
        long matchingInk = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = before.getRGB(x, y);
                int b = after.getRGB(x, y);
                boolean matches = withinTolerance(a, b);

                if (excluded.contains(x, y)) {
                    // Blue marks the deliberately edited band, so it reads differently from damage.
                    diff.setRGB(x, y, hasInk(a) || hasInk(b) ? 0xFF3355FF : fade(a));
                    continue;
                }

                if (hasInk(a) || hasInk(b)) {
                    inkPixels++;
                    if (matches) {
                        matchingInk++;
                    }
                }

                // Faded original so the red mask reads against the page content.
                diff.setRGB(x, y, matches ? fade(a) : 0xFFFF0000);
            }
        }

        ImageIO.write(diff, "png", dir.resolve("diff.png").toFile());
        return new Comparison(inkPixels == 0 ? 1.0 : matchingInk / (double) inkPixels, inkPixels);
    }

    /** True when the pixel carries content rather than blank page. */
    private static boolean hasInk(int rgb) {
        return ((rgb >> 16) & 0xFF) < INK_THRESHOLD
                || ((rgb >> 8) & 0xFF) < INK_THRESHOLD
                || (rgb & 0xFF) < INK_THRESHOLD;
    }

    private static boolean withinTolerance(int rgbA, int rgbB) {
        return Math.abs(((rgbA >> 16) & 0xFF) - ((rgbB >> 16) & 0xFF)) <= CHANNEL_TOLERANCE
                && Math.abs(((rgbA >> 8) & 0xFF) - ((rgbB >> 8) & 0xFF)) <= CHANNEL_TOLERANCE
                && Math.abs((rgbA & 0xFF) - (rgbB & 0xFF)) <= CHANNEL_TOLERANCE;
    }

    private static int fade(int rgb) {
        int r = 255 - ((255 - ((rgb >> 16) & 0xFF)) / 4);
        int g = 255 - ((255 - ((rgb >> 8) & 0xFF)) / 4);
        int b = 255 - ((255 - (rgb & 0xFF)) / 4);
        return (r << 16) | (g << 8) | b;
    }

    @FunctionalInterface
    interface PdfSupplier {
        byte[] get() throws IOException;
    }

    // ------------------------------------------------------------------
    // Fixtures - one per failure mode called out in the editor's known issues
    // ------------------------------------------------------------------

    /**
     * Stamps a small image onto page 1, which forces {@code determineRegenerateMode} down {@code
     * REGENERATE_WITH_VECTOR_OVERLAY} instead of the surgical {@code rewriteTextOperators} fast
     * path.
     *
     * <p>This distinction is the whole game. A pure-text page gets its text operators patched in
     * place, so it survives the trip untouched and scores a perfect 1.0. Put an image on the page -
     * or use a font needing fallback - and the entire page is rebuilt from the JSON model, which is
     * where colour, transparency and layering actually get lost. It is also exactly why the tool
     * advertises itself as good for letters and poor for brochures.
     */
    private static byte[] withImage(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            BufferedImage swatch = new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = swatch.createGraphics();
            graphics.setColor(new Color(0x2E7D32));
            graphics.fillRect(0, 0, 48, 48);
            graphics.dispose();

            PDImageXObject image = LosslessFactory.createFromImage(document, swatch);
            try (PDPageContentStream cs =
                    new PDPageContentStream(
                            document, document.getPage(0), AppendMode.APPEND, true, true)) {
                cs.drawImage(image, 430f, 80f, 48f, 48f);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] toBytes(PDDocument document) throws IOException {
        try (document) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static PDDocument letterPage() {
        PDDocument document = new PDDocument();
        document.addPage(new PDPage(PDRectangle.LETTER));
        return document;
    }

    /** Control fixture: if this one drifts, something fundamental broke. */
    private static byte[] plainTextPdf() throws IOException {
        PDDocument document = letterPage();
        try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(0))) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f);
            cs.newLineAtOffset(72, 700);
            cs.showText("The quick brown fox jumps over the lazy dog.");
            cs.newLineAtOffset(0, -16);
            cs.showText("Second line for leading and baseline checks.");
            cs.endText();
        }
        return toBytes(document);
    }

    /** Known issue 4: rotated text alignment. */
    private static byte[] rotatedTextPdf() throws IOException {
        PDDocument document = letterPage();
        try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(0))) {
            for (int degrees : new int[] {30, 90, -45}) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f);
                cs.setTextMatrix(
                        Matrix.getRotateInstance(Math.toRadians(degrees), 160f, 250f + degrees));
                cs.showText("Rotated " + degrees + " degrees");
                cs.endText();
            }
        }
        return toBytes(document);
    }

    /** Known issue 1: colour preservation, including non-RGB colour spaces. */
    private static byte[] cmykColourPdf() throws IOException {
        PDDocument document = letterPage();
        try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(0))) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18f);
            cs.newLineAtOffset(72, 700);
            cs.setNonStrokingColor(
                    new PDColor(new float[] {0f, 0.9f, 0.9f, 0f}, PDDeviceCMYK.INSTANCE));
            cs.showText("CMYK red heading");
            cs.newLineAtOffset(0, -30);
            cs.setNonStrokingColor(0.1f, 0.3f, 0.8f);
            cs.showText("RGB blue body text");
            cs.newLineAtOffset(0, -30);
            cs.setNonStrokingColor(0.5f);
            cs.showText("Grayscale caption");
            cs.endText();
        }
        return toBytes(document);
    }

    /** Known issue 2: paragraph grouping across columns. */
    private static byte[] multiColumnPdf() throws IOException {
        PDDocument document = letterPage();
        try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(0))) {
            for (float columnX : new float[] {72f, 330f}) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f);
                cs.setLeading(14f);
                cs.newLineAtOffset(columnX, 700);
                for (int line = 1; line <= 6; line++) {
                    cs.showText("Column at " + (int) columnX + ", line " + line);
                    cs.newLine();
                }
                cs.endText();
            }
        }
        return toBytes(document);
    }

    /** Known issue 5: transparency and layering. */
    private static byte[] transparencyPdf() throws IOException {
        PDDocument document = letterPage();
        try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(0))) {
            PDExtendedGraphicsState translucent = new PDExtendedGraphicsState();
            translucent.setNonStrokingAlphaConstant(0.4f);

            cs.setNonStrokingColor(0.9f, 0.2f, 0.2f);
            cs.addRect(100, 600, 200, 100);
            cs.fill();

            cs.saveGraphicsState();
            cs.setGraphicsStateParameters(translucent);
            cs.setNonStrokingColor(0.2f, 0.2f, 0.9f);
            cs.addRect(200, 650, 200, 100);
            cs.fill();

            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 20f);
            cs.newLineAtOffset(120, 640);
            cs.showText("Translucent text");
            cs.endText();
            cs.restoreGraphicsState();
        }
        return toBytes(document);
    }

    /** Font size and leading variety, which drives the auto-scale and grouping heuristics. */
    private static byte[] mixedSizesPdf() throws IOException {
        PDDocument document = letterPage();
        try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(0))) {
            float y = 720f;
            for (float size : new float[] {24f, 18f, 12f, 9f}) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
                cs.newLineAtOffset(72, y);
                cs.showText("Heading at " + (int) size + "pt with descenders: gjpqy");
                cs.endText();
                y -= size * 2.2f;
            }
        }
        return toBytes(document);
    }
}
