package io.github.vlsergey.secan4j.core.session;

import static io.github.vlsergey.secan4j.core.colored.ColorType.SourceData;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

import io.github.vlsergey.secan4j.core.colored.ColorType;
import io.github.vlsergey.secan4j.core.colored.SimpleColoredMethods;

class SimpleColoredMethodsTest extends BasePaintingSessionTest {

	@Test
	void testAppend() throws Exception {
		assertArrayEquals(new ColorType[][] { { null, SourceData, SourceData }, {} },
				analyze(SimpleColoredMethods.class, "append"));
	}

	@Test
	void testConcatenation() throws Exception {
		assertArrayEquals(new ColorType[][] { { null, SourceData, SourceData }, { SourceData } },
				analyze(SimpleColoredMethods.class, "concatenation"));
	}

	@Test
	void testPrepareStatement() throws Exception {
		assertArrayEquals(new ColorType[][] { { null, null, SourceData }, { null } },
				analyze(SimpleColoredMethods.class, "prepareStatement"));
	}

}