package com.agustin.corebanking.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Validación y formato de dinero. Nunca se redondea: lo que no cabe en 2 decimales se rechaza. */
public final class Amounts {

	public static final BigDecimal MAX = new BigDecimal("1000000");

	private Amounts() {
	}

	/** Positivo y con a lo sumo 2 decimales; devuelve el monto con escala 2. */
	public static BigDecimal requireValid(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
			throw BusinessException.unprocessable("INVALID_AMOUNT",
					"El monto debe ser mayor a cero y tener hasta 2 decimales");
		}
		return amount.setScale(2, RoundingMode.UNNECESSARY);
	}

	/** Igual que {@link #requireValid} y además <= 1.000.000. */
	public static BigDecimal requireValidWithinLimit(BigDecimal amount) {
		BigDecimal valid = requireValid(amount);
		if (valid.compareTo(MAX) > 0) {
			throw BusinessException.unprocessable("AMOUNT_LIMIT_EXCEEDED", "El monto supera el máximo permitido");
		}
		return valid;
	}

	/** String decimal para JSON y mensajes: siempre 2 decimales, sin notación científica. */
	public static String format(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
	}

}
