package com.agustin.corebanking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class AmountsTest {

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.00", "-1", "-0.01", "1.234", "10.001" })
	void rejectsNonPositiveOrMoreThanTwoDecimals(String value) {
		assertThatThrownBy(() -> Amounts.requireValid(new BigDecimal(value)))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_AMOUNT"));
	}

	@Test
	void rejectsNull() {
		assertThatThrownBy(() -> Amounts.requireValid(null)).isInstanceOf(BusinessException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "1", "1.5", "1.50", "1.500", "1E+3", "1500.00", "0.01" })
	void acceptsUpToTwoSignificantDecimalsAndNormalizesScale(String value) {
		assertThat(Amounts.requireValid(new BigDecimal(value)).scale()).isEqualTo(2);
	}

	@Test
	void neverRounds() {
		// 1.005 no se redondea a 1.01: se rechaza
		assertThatThrownBy(() -> Amounts.requireValid(new BigDecimal("1.005"))).isInstanceOf(BusinessException.class);
	}

	@Test
	void limitIsInclusiveAtOneMillion() {
		assertThat(Amounts.requireValidWithinLimit(new BigDecimal("1000000"))).isEqualByComparingTo("1000000");
		assertThatThrownBy(() -> Amounts.requireValidWithinLimit(new BigDecimal("1000000.01")))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getCode()).isEqualTo("AMOUNT_LIMIT_EXCEEDED"));
	}

	@Test
	void formatsAlwaysWithTwoDecimalsAsPlainString() {
		assertThat(Amounts.format(new BigDecimal("1500.0000"))).isEqualTo("1500.00");
		assertThat(Amounts.format(new BigDecimal("1E+3"))).isEqualTo("1000.00");
		assertThat(Amounts.format(BigDecimal.ZERO)).isEqualTo("0.00");
	}

	@Test
	void formatRefusesToRoundSilently() {
		assertThatThrownBy(() -> Amounts.format(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void requestHashIsStableHexAndSensitiveToEveryPart() {
		String a = RequestHasher.sha256("DEMO_FAUCET", "acc-1", "50000.00");

		assertThat(a).hasSize(64).matches("[0-9a-f]+");
		assertThat(RequestHasher.sha256("DEMO_FAUCET", "acc-1", "50000.00")).isEqualTo(a);
		assertThat(RequestHasher.sha256("DEMO_FAUCET", "acc-1", "50000.01")).isNotEqualTo(a);
		assertThat(RequestHasher.sha256("ADMIN", "acc-1", "50000.00")).isNotEqualTo(a);
		assertThat(RequestHasher.sha256("DEMO_FAUCET", "acc-2", "50000.00")).isNotEqualTo(a);
	}

}
