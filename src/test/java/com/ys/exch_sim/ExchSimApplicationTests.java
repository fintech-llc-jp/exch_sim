package com.ys.exch_sim;

import com.ys.exch_sim.config.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class ExchSimApplicationTests {

	@Test
	void contextLoads() {
	}

}
