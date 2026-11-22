package plugins.quorum.Libraries.Fabrication.Geometry;

public class VoronoiNative {
    public java.lang.Object me_ = null;

    private static final String loadError;
    static {
        String err = null;
        try {
            // avoid needing to edit path by checking next to lamprey-plugins.jar
            java.io.File jarDir = new java.io.File(
                VoronoiNative.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
            java.io.File lib = new java.io.File(jarDir, System.mapLibraryName("voronoi"));
            if (lib.isFile()) {
                System.load(lib.getAbsolutePath());
            } else {
                System.loadLibrary("voronoi");
            }
        } catch (Throwable t) {
            err = t.toString();
        }
        loadError = err;
    }

    // success returns 0 otherwise runtime exception for issue
    public int Build(String inputPath, String outputPath) {
        if (loadError != null) {
            throw new RuntimeException("VoronoiNative: could not load voronoi library ("
                + loadError + "). java.library.path = " + System.getProperty("java.library.path"));
        }
        int status = nativeBuild(inputPath, outputPath);
        if (status != 0) {
            throw new RuntimeException("VoronoiNative: Boost bridge failed with code " + status);
        }
        return status;
    }

    private static native int nativeBuild(String inputPath, String outputPath);
}