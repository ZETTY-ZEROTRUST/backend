package com.zeti.bff.vault;

/** vault 항목을 복호화할 수 없다(암호문 변조, AAD 불일치, 알 수 없는 키 버전). 해당 세션은 재로그인해야 한다. */
public class VaultUnreadableException extends RuntimeException {

    public VaultUnreadableException(String message) {
        super(message);
    }

    public VaultUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
