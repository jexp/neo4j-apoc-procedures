package apoc.result;

public class ByteArrayResult {
    public final static ByteArrayResult NULL = new ByteArrayResult(null);

    public final byte[] value;

    public ByteArrayResult(byte[] value) {
        this.value = value;
    }
}