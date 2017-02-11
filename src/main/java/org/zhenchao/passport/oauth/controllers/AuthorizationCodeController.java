package org.zhenchao.passport.oauth.controllers;

import net.sf.json.JSONObject;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;
import org.zhenchao.passport.oauth.commons.ErrorCode;
import org.zhenchao.passport.oauth.commons.GlobalConstant;
import static org.zhenchao.passport.oauth.commons.GlobalConstant.COOKIE_KEY_USER_LOGIN_SIGN;
import static org.zhenchao.passport.oauth.commons.GlobalConstant.PATH_OAUTH_AUTHORIZE_CODE;
import static org.zhenchao.passport.oauth.commons.GlobalConstant.PATH_OAUTH_AUTHORIZE_TOKEN;
import static org.zhenchao.passport.oauth.commons.GlobalConstant.PATH_OAUTH_USER_AUTHORIZE;
import static org.zhenchao.passport.oauth.commons.GlobalConstant.PATH_ROOT_LOGIN;
import static org.zhenchao.passport.oauth.commons.GlobalConstant.PATH_ROOT_OAUTH;
import org.zhenchao.passport.oauth.domain.AuthorizationCode;
import org.zhenchao.passport.oauth.domain.AuthorizationCodeParams;
import org.zhenchao.passport.oauth.domain.AuthorizationTokenParams;
import org.zhenchao.passport.oauth.domain.ErrorInformation;
import org.zhenchao.passport.oauth.exceptions.OAuthServiceException;
import org.zhenchao.passport.oauth.model.OAuthAppInfo;
import org.zhenchao.passport.oauth.model.Scope;
import org.zhenchao.passport.oauth.model.User;
import org.zhenchao.passport.oauth.model.UserAppAuthorization;
import org.zhenchao.passport.oauth.service.AuthorizeService;
import org.zhenchao.passport.oauth.service.OAuthAppInfoService;
import org.zhenchao.passport.oauth.service.ParamsValidateService;
import org.zhenchao.passport.oauth.service.ScopeService;
import org.zhenchao.passport.oauth.service.UserAppAuthorizationService;
import org.zhenchao.passport.oauth.token.AbstractAccessToken;
import org.zhenchao.passport.oauth.token.MacAccessToken;
import org.zhenchao.passport.oauth.token.generator.AbstractAccessTokenGenerator;
import org.zhenchao.passport.oauth.token.generator.AbstractTokenGenerator;
import org.zhenchao.passport.oauth.token.generator.TokenGeneratorFactory;
import org.zhenchao.passport.oauth.utils.CommonUtils;
import org.zhenchao.passport.oauth.utils.CookieUtils;
import org.zhenchao.passport.oauth.utils.HttpRequestUtils;
import org.zhenchao.passport.oauth.utils.JSONView;
import org.zhenchao.passport.oauth.utils.SessionUtils;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 授权码模式授权控制器
 *
 * @author zhenchao.wang 2016-12-19 21:19
 * @version 1.0.0
 */
@Controller
@RequestMapping(PATH_ROOT_OAUTH)
public class AuthorizationCodeController {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationCodeController.class);

    @Resource
    private OAuthAppInfoService appInfoService;

    @Resource
    private UserAppAuthorizationService authorizationService;

    @Resource
    private ScopeService scopeService;

    @Resource
    private ParamsValidateService paramsValidateService;

    @Resource
    private AuthorizeService authorizeService;

    /**
     * issue authorization code
     *
     * @return
     */
    @RequestMapping(value = PATH_OAUTH_AUTHORIZE_CODE, method = {GET, POST}, params = "response_type=code")
    public ModelAndView authorize(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            @RequestParam("response_type") String responseType,
            @RequestParam("client_id") long clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "skip_confirm", required = false, defaultValue = "false") boolean skipConfirm,
            @RequestParam(value = "force_login", required = false, defaultValue = "false") boolean forceLogin) {

        log.debug("Entering authorize code method...");

        ModelAndView mav = new ModelAndView();

        AuthorizationCodeParams codeParams = new AuthorizationCodeParams().setResponseType(responseType).setClientId(clientId)
                .setRedirectUri(redirectUri).setScope(scope).setState(StringUtils.trimToEmpty(state));
        // 校验授权请求参数
        ErrorCode validateResult = paramsValidateService.validateCodeRequestParams(codeParams);
        if (!ErrorCode.NO_ERROR.equals(validateResult)) {
            // 请求参数有误
            log.error("Request authorize params error, params[{}], errorCode[{}]", codeParams, validateResult);
            return JSONView.render(new ErrorInformation(validateResult, state), response);
        }

        // 获取APP信息
        Optional<OAuthAppInfo> optAppInfo = appInfoService.getAppInfo(clientId);
        if (!optAppInfo.isPresent()) {
            log.error("Client[id={}] is not exist!", clientId);
            return JSONView.render(new ErrorInformation(ErrorCode.CLIENT_NOT_EXIST, state), response);
        }

        OAuthAppInfo appInfo = optAppInfo.get();
        User user = SessionUtils.getUser(session, CookieUtils.get(request, COOKIE_KEY_USER_LOGIN_SIGN));
        if (null == user || forceLogin) {
            // 用户未登录或需要强制登录，跳转到登录页面
            UriComponentsBuilder builder = UriComponentsBuilder.newInstance();
            builder.path(PATH_ROOT_LOGIN)
                    .queryParam(GlobalConstant.CALLBACK, HttpRequestUtils.getEncodeRequestUrl(request))
                    .queryParam("app_name", appInfo.getAppName());
            mav.setViewName("redirect:" + builder.toUriString());
            return mav;
        }

        Optional<UserAppAuthorization> authorization =
                authorizationService.getUserAndAppAuthorizationInfo(
                        user.getId(), codeParams.getClientId(), CommonUtils.genScopeSign(codeParams.getScope()));

        if (authorization.isPresent() && !skipConfirm) {
            // 用户已授权该APP，下发授权码
            try {
                Optional<AuthorizationCode> optCode = authorizeService.generateAndCacheAuthorizationCode(authorization.get(), codeParams);
                if (optCode.isPresent()) {
                    // 下发授权码
                    String code = optCode.get().getValue();
                    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(appInfo.getRedirectUri());
                    builder.queryParam("code", code);
                    if (StringUtils.isNotEmpty(state)) {
                        builder.queryParam("state", state);
                    }
                    mav.setViewName("redirect:" + builder.toUriString());
                    return mav;
                }
                return JSONView.render(new ErrorInformation(ErrorCode.GENERATE_CODE_ERROR, state), response);
            } catch (OAuthServiceException e) {
                log.error("Generate authorization code error!", e);
                return JSONView.render(new ErrorInformation(e.getErrorCode(), state), response);
            }
        } else {
            // 用户未授权该APP，跳转到授权页面
            List<Scope> scopes = scopeService.getScopes(codeParams.getScope());
            UriComponentsBuilder builder = UriComponentsBuilder.newInstance();
            builder.path(PATH_ROOT_OAUTH + PATH_OAUTH_USER_AUTHORIZE).queryParam(GlobalConstant.CALLBACK, HttpRequestUtils.getEncodeRequestUrl(request));
            mav.setViewName("user-authorize");
            mav.addObject(GlobalConstant.CALLBACK, builder.build(true))
                    .addObject("scopes", scopes).addObject("user", user).addObject("app", appInfo).addObject("state", StringUtils.trimToEmpty(state));
            return mav;
        }

    }

    /**
     * issue accessToken (and refreshToken)
     *
     * @return
     */
    @RequestMapping(value = PATH_OAUTH_AUTHORIZE_TOKEN, method = {GET, POST}, params = "grant_type=authorization_code")
    public ModelAndView issueAccessToken(
            HttpServletResponse response,
            @RequestParam("grant_type") String grantType,
            @RequestParam("code") String code,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("client_id") long clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestParam(value = "token_type", required = false) String tokenType,
            @RequestParam(value = "issue_refresh_token", required = false, defaultValue = "true") boolean refresh) {

        log.debug("Entering authorize code method...");

        AuthorizationTokenParams tokenParams = new AuthorizationTokenParams();
        tokenParams.setGrantType(grantType).setCode(code).setRedirectUri(redirectUri).setClientId(clientId)
                .setTokenType(StringUtils.defaultString(tokenType, AbstractAccessToken.TokenType.MAC.getValue()))
                .setClientSecret(clientSecret).setIrt(refresh);

        ErrorCode validateResult = paramsValidateService.validateTokenRequestParams(tokenParams);
        if (!ErrorCode.NO_ERROR.equals(validateResult)) {
            log.error("Params error when request token, params [{}], error code [{}]", tokenParams, validateResult);
            return JSONView.render(new ErrorInformation(validateResult, StringUtils.EMPTY), response);
        }

        // 校验用户与APP之间是否存在授权关系
        AuthorizationCode ac = tokenParams.getAuthorizationCode();
        Optional<UserAppAuthorization> optuaa = authorizationService.getUserAndAppAuthorizationInfo(
                ac.getUserId(), ac.getAppInfo().getAppId(), CommonUtils.genScopeSign(ac.getScopes()));
        if (!optuaa.isPresent()) {
            // 用户与APP之间不存在指定的授权关系
            log.error("No authorization between user[{}] and app[{}] on scope[{}]!", ac.getUserId(), ac.getAppInfo().getAppId(), ac.getScopes());
            return JSONView.render(new ErrorInformation(ErrorCode.UNAUTHORIZED_CLIENT, StringUtils.EMPTY), response);
        }
        tokenParams.setUserAppAuthorization(optuaa.get());

        // 验证通过，下发accessToken
        Optional<AbstractTokenGenerator> optTokenGenerator = TokenGeneratorFactory.getGenerator(tokenParams);
        if (!optTokenGenerator.isPresent()) {
            log.error("Unknown grantType[{}] or tokenType[{}]", tokenParams.getGrantType(), tokenParams.getTokenType());
            return JSONView.render(new ErrorInformation(ErrorCode.UNSUPPORTED_GRANT_TYPE, StringUtils.EMPTY), response);
        }

        AbstractAccessTokenGenerator accessTokenGenerator = (AbstractAccessTokenGenerator) optTokenGenerator.get();
        Optional<AbstractAccessToken> optAccessToken = accessTokenGenerator.create();
        if (!optAccessToken.isPresent()) {
            log.error("Generate access token failed, params[{}]", tokenParams);
            return JSONView.render(new ErrorInformation(ErrorCode.INVALID_REQUEST, StringUtils.EMPTY), response);
        }

        // no cache
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");

        AbstractAccessToken accessToken = optAccessToken.get();
        try {
            JSONObject result = new JSONObject();
            result.put("access_token", accessToken.getValue());
            result.put("expires_in", accessToken.getExpirationTime());
            result.put("refresh_token", refresh ? accessToken.getRefreshToken() : StringUtils.EMPTY);
            if (accessToken instanceof MacAccessToken) {
                result.put("mac_key", accessToken.getKey());
                result.put("mac_algorithm", ((MacAccessToken) accessToken).getAlgorithm().getValue());
            }
            // TODO add json safe prefix
            return JSONView.render(result, response);
        } catch (IOException e) {
            log.error("Get string access token error by [{}]!", accessToken);
        }

        return JSONView.render(new ErrorInformation(ErrorCode.SERVICE_ERROR, StringUtils.EMPTY), response);
    }

    /**
     * user authorize on app
     *
     * @return
     */
    @RequestMapping(value = PATH_OAUTH_USER_AUTHORIZE, method = {POST})
    public ModelAndView userAuthorize(
            HttpServletResponse response,
            @RequestParam("user_id") long userId,
            @RequestParam("client_id") long clientId,
            @RequestParam("scope") String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam("callback") String callback) {

        log.debug("Entering user authorize method...");

        ModelAndView mav = new ModelAndView();

        if (userId < 0 || StringUtils.isBlank(callback)) {
            log.error("User authorize request params error, userId[{}], callback[{}]", userId, callback);
            UriComponentsBuilder builder = UriComponentsBuilder.newInstance();
            builder.path(PATH_ROOT_LOGIN);
            builder.queryParam("callback", callback);
            mav.setViewName("redirect:" + builder.toUriString());
            return mav;
        }

        // 添加用户授权关系
        UserAppAuthorization authorization = new UserAppAuthorization();
        authorization.setUserId(userId);
        authorization.setAppId(clientId);
        authorization.setScope(scope);
        authorization.setScopeSign(CommonUtils.genScopeSign(scope));
        authorization.setCreateTime(new Date());
        authorization.setCreateTime(authorization.getCreateTime());
        authorization.setTokenKey(RandomStringUtils.randomAlphanumeric(64));  // 随机生成key
        authorization.setRefreshTokenKey(RandomStringUtils.randomAlphanumeric(64));  // FIXME 这里是不是应该考虑采用AES加密
        authorization.setRefreshTokenExpirationTime(365 * 24 * 3600L);  // 设置刷新令牌有效期
        if (authorizationService.replaceUserAndAppAuthorizationInfo(authorization)) {
            // 更新用户授权关系成功
            mav.setViewName("redirect:" + callback);
            return mav;
        }
        return JSONView.render(new ErrorInformation(ErrorCode.LOCAL_SERVICE_ERROR, state), response);
    }

}
