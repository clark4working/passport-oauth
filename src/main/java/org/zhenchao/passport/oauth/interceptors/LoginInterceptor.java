package org.zhenchao.passport.oauth.interceptors;

import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;
import static org.zhenchao.passport.oauth.commons.GlobalConstant.AES_KEY;
import static org.zhenchao.passport.oauth.commons.GlobalConstant.COOKIE_KEY_USER_LOGIN_SIGN;
import org.zhenchao.passport.oauth.utils.CookieUtils;
import org.zhenchao.passport.oauth.utils.HttpRequestUtils;
import org.zhenchao.passport.oauth.utils.SessionUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录验证拦截器
 *
 * @author zhenchao.wang 2017-01-02 15:25
 * @version 1.0.0
 */
public class LoginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        String key = CookieUtils.get(request, COOKIE_KEY_USER_LOGIN_SIGN);
        if (StringUtils.isBlank(key)) {
            String username = request.getParameter("username");
            if(StringUtils.isNotBlank(username)) {
                key = HmacUtils.hmacSha1Hex(AES_KEY, username);
            }
        }

        if (StringUtils.isNotBlank(key)) {
            if (null != SessionUtils.getUser(request.getSession(), key)) {
                return true;
            }
        }

        // 用户未登录，跳转到登录页
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance();
        builder.path("/login").queryParam("callback", HttpRequestUtils.getEncodeRequestUrl(request));
        response.sendRedirect(builder.build().toUriString());

        return false;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView)
            throws Exception {

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

    }
}