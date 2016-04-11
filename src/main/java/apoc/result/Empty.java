package apoc.result;

import java.util.stream.Stream;

/**
 * @author mh
 * @since 09.04.16
 */
public class Empty {
    public static Empty INSTANCE = new Empty();

    public static Stream<Empty> stream(boolean fillWithStub) {
		return fillWithStub ? Stream.of(INSTANCE) : Stream.empty();
	}
}
