package com.agustin.corebanking.crypto;

/** Enmascara números de cuenta para respuestas a terceros y para logs: {@code ****1234}. */
public final class AccountNumberMasker {

	private AccountNumberMasker() {
	}

	public static String mask(String accountNumber) {
		if (accountNumber == null || accountNumber.length() <= 4) {
			return "****";
		}
		return "****" + accountNumber.substring(accountNumber.length() - 4);
	}

}
