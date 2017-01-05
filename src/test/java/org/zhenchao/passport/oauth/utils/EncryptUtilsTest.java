package org.zhenchao.passport.oauth.utils;

import org.junit.Test;
import org.zhenchao.passport.oauth.commons.GlobalConstant;

/**
 * @author zhenchao.wang 2017-01-05 17:54
 * @version 1.0.0
 */
public class EncryptUtilsTest {
    @Test
    public void pbkdf2() throws Exception {
        System.out.println(EncryptUtils.pbkdf2("123456", GlobalConstant.SALT));
    }

}