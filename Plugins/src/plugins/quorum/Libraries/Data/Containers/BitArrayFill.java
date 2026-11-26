package plugins.quorum.Libraries.Data.Containers;

import java.util.Arrays;

import quorum.Libraries.Containers.Integer32BitArray;
import quorum.Libraries.Containers.Integer32BitArray_;
import quorum.Libraries.Containers.Number32BitArray;
import quorum.Libraries.Containers.Number32BitArray_;
import quorum.Libraries.Containers.Number64BitArray;
import quorum.Libraries.Containers.Number64BitArray_;

/**
 * Java side of Libraries.Data.Containers.BitArrayFill: fills the standard
 * library's Integer32BitArray, Number32BitArray and Number64BitArray with
 * java.util.Arrays.fill, without changing or rebuilding the Quorum standard
 * library. It reaches each array's Java storage the same way Quorum's own
 * plugins do (DesktopGraphics, for one): through the public plugin_ field the
 * compiler gives every class with system actions, and that plugin's public
 * ints / floats / doubles array.
 *
 * The range checks are done on the Quorum side, which raises
 * InvalidLocationError; here a range is [from, to), as in Arrays.fill.
 *
 * Compiling this needs the Quorum runtime classes it names. Plugins/stubs
 * holds compile-only stand-ins for them (not packaged into the jar); at run
 * time the real classes from the Quorum runtime are used.
 */
public class BitArrayFill {
    public java.lang.Object me_ = null;

    public void FillInteger(Integer32BitArray_ array, int value) {
        Arrays.fill(((Integer32BitArray) array).plugin_.ints, value);
    }

    public void FillIntegerRange(Integer32BitArray_ array, int from, int to, int value) {
        Arrays.fill(((Integer32BitArray) array).plugin_.ints, from, to, value);
    }

    public void FillNumber32(Number32BitArray_ array, double value) {
        Arrays.fill(((Number32BitArray) array).plugin_.floats, (float) value);
    }

    public void FillNumber32Range(Number32BitArray_ array, int from, int to, double value) {
        Arrays.fill(((Number32BitArray) array).plugin_.floats, from, to, (float) value);
    }

    public void FillNumber64(Number64BitArray_ array, double value) {
        Arrays.fill(((Number64BitArray) array).plugin_.doubles, value);
    }

    public void FillNumber64Range(Number64BitArray_ array, int from, int to, double value) {
        Arrays.fill(((Number64BitArray) array).plugin_.doubles, from, to, value);
    }
}
