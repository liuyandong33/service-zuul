package build.dream.gateway.controllers;

import build.dream.common.beans.WebResponse;
import build.dream.common.beans.WeiXinAccessToken;
import build.dream.common.beans.WeiXinOAuthToken;
import build.dream.common.beans.WeiXinUserInfo;
import build.dream.common.constants.Constants;
import build.dream.common.saas.domains.WeiXinAuthorizerInfo;
import build.dream.common.saas.domains.WeiXinAuthorizerToken;
import build.dream.common.saas.domains.WeiXinOpenPlatformApplication;
import build.dream.common.saas.domains.WeiXinPublicAccount;
import build.dream.common.utils.*;
import build.dream.gateway.models.weixin.ObtainUserInfoModel;
import build.dream.gateway.services.WeiXinService;
import net.sf.json.JSONObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.dom4j.DocumentException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.*;

@Controller
@RequestMapping(value = "/weiXin")
public class WeiXinController {
    @Autowired
    private WeiXinService weiXinService;

    @RequestMapping(value = "/obtainUserInfo", method = RequestMethod.GET)
    public String obtainUserInfo() throws Exception {
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        ObtainUserInfoModel obtainUserInfoModel = ApplicationHandler.instantiateObject(ObtainUserInfoModel.class, requestParameters);
        obtainUserInfoModel.validateAndThrow();

        String appId = obtainUserInfoModel.getAppId();
        String scope = obtainUserInfoModel.getScope();
        String redirectUri = obtainUserInfoModel.getRedirectUri();
        String state = obtainUserInfoModel.getState();

        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put("redirectUri", redirectUri);
        parameters.put("appId", appId);

        String outsideUrl = CommonUtils.getOutsideUrl(Constants.SERVICE_NAME_GATEWAY, "weiXin", "oauthCallback") + "?" + WebUtils.buildQueryString(parameters);
        String authorizeUrl = WeiXinUtils.generateAuthorizeUrl(appId, scope, outsideUrl, state);
        return "redirect:" + authorizeUrl;
    }

    @RequestMapping(value = "/oauthCallback", method = RequestMethod.GET)
    public String oauthCallback() throws IOException {
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        String code = requestParameters.get("code");
        String redirectUri = requestParameters.get("redirectUri");
        String appId = requestParameters.get("appId");
        String state = requestParameters.get("state");

        WeiXinPublicAccount weiXinPublicAccount = weiXinService.obtainWeiXinPublicAccount(appId);
        ValidateUtils.notNull(weiXinPublicAccount, "微信公众号不存在！");

        WeiXinOAuthToken weiXinOAuthToken = WeiXinUtils.obtainOAuthToken(appId, weiXinPublicAccount.getAppSecret(), code);

        Map<String, String> parameters = new HashMap<String, String>();

        String openId = weiXinOAuthToken.getOpenId();
        parameters.put("openId", openId);

        String scope = weiXinOAuthToken.getScope();
        if (Constants.SNSAPI_BASE.equals(scope)) {

        } else if (Constants.SNSAPI_USERINFO.equals(scope)) {
            WeiXinUserInfo weiXinUserInfo = WeiXinUtils.obtainUserInfo(weiXinOAuthToken.getAccessToken(), openId, null);
            parameters.put("userInfo", GsonUtils.toJson(weiXinUserInfo));
        }

        if (StringUtils.isNotBlank(state)) {
            parameters.put("state", state);
        }

        StringBuilder url = new StringBuilder(redirectUri);
        if (redirectUri.indexOf("?") >= 0) {
            url.append("&");
        } else {
            url.append("?");
        }
        return "redirect:" + url.append(WebUtils.buildQueryString(parameters)).toString();
    }

    @RequestMapping(value = "/authCallback")
    @ResponseBody
    public String authCallback(HttpServletRequest httpServletRequest) throws IOException, DocumentException {
        InputStream inputStream = httpServletRequest.getInputStream();
        String requestBody = IOUtils.toString(inputStream);
        Map<String, String> requestBodyMap = XmlUtils.xmlStringToMap(requestBody);
        String appId = requestBodyMap.get("AppId");
        String encrypt = requestBodyMap.get("Encrypt");

        WeiXinOpenPlatformApplication weiXinOpenPlatformApplication = weiXinService.obtainWeiXinOpenPlatformApplication(appId);
        if (weiXinOpenPlatformApplication == null) {
            return Constants.SUCCESS;
        }
        String encodingAesKey = weiXinOpenPlatformApplication.getEncodingAesKey();
        String xmlContent = decrypt(encrypt, encodingAesKey);
        Map<String, String> encryptMap = XmlUtils.xmlStringToMap(xmlContent);

        ValidateUtils.isTrue(appId.equals(encryptMap.get("AppId")), "消息内容非法！");

        String componentVerifyTicket = encryptMap.get("ComponentVerifyTicket");
        CacheUtils.hset(Constants.KEY_WEI_XIN_COMPONENT_VERIFY_TICKET, appId, componentVerifyTicket);
        return Constants.SUCCESS;
    }

    private String decrypt(String data, String encodingAesKey) {
        byte[] encryptedData = Base64.decodeBase64(data);
        byte[] aesKey = Base64.decodeBase64(encodingAesKey);
        byte[] iv = Arrays.copyOfRange(aesKey, 0, 16);

        byte[] original = AESUtils.decrypt(encryptedData, aesKey, iv, AESUtils.ALGORITHM_AES_CBC_NOPADDING);
        byte[] bytes = original = decode(original);

        byte[] networkOrder = Arrays.copyOfRange(bytes, 16, 20);
        int xmlLength = recoverNetworkBytesOrder(networkOrder);

        String plaintext = new String(Arrays.copyOfRange(original, 20, 20 + xmlLength), Constants.CHARSET_UTF_8);
        return plaintext;
    }

    private byte[] decode(byte[] decrypted) {
        int pad = (int) decrypted[decrypted.length - 1];
        if (pad < 1 || pad > 32) {
            pad = 0;
        }
        return Arrays.copyOfRange(decrypted, 0, decrypted.length - pad);
    }

    private int recoverNetworkBytesOrder(byte[] orderBytes) {
        int sourceNumber = 0;
        for (int i = 0; i < 4; i++) {
            sourceNumber <<= 8;
            sourceNumber |= orderBytes[i] & 0xff;
        }
        return sourceNumber;
    }

    @RequestMapping(value = "/demo")
    public ModelAndView demo() throws IOException {
        String componentAppId = "wx3465dea1e67a3131";
        String componentAppSecret = "587ad4920d1767e10ce7503da86ac1a3";
        String preAuthCode = WeiXinUtils.obtainPreAuthCode(componentAppId, componentAppSecret);
        String redirectUri = "http://check-local.smartpos.top/zd1/ct2/weiXin/callback?tenantId=100&componentAppId=" + componentAppId;
        String url = WeiXinUtils.generateComponentLoginPageUrl(componentAppId, preAuthCode, redirectUri, "3");
        ModelAndView modelAndView = new ModelAndView();
        modelAndView.setViewName("weiXin/demo");
        modelAndView.addObject("url", url);
        return modelAndView;
    }

    @RequestMapping(value = "/callback")
    @ResponseBody
    public String callback() throws IOException {
        String tenantId = ApplicationHandler.getRequestParameter("tenantId");
        String componentAppId = ApplicationHandler.getRequestParameter("componentAppId");
        String authorizationCode = ApplicationHandler.getRequestParameter("auth_code");
        SearchModel searchModel = new SearchModel(true);
        searchModel.addSearchCondition(WeiXinOpenPlatformApplication.ColumnName.APP_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, componentAppId);

        WeiXinOpenPlatformApplication weiXinOpenPlatformApplication = DatabaseHelper.find(WeiXinOpenPlatformApplication.class, searchModel);

        String componentAppSecret = weiXinOpenPlatformApplication.getAppSecret();
        String componentAccessToken = WeiXinUtils.obtainComponentAccessToken(componentAppId, componentAppSecret).getComponentAccessToken();
        WeiXinAuthorizerToken weiXinAuthorizerToken = WeiXinUtils.apiQueryAuth(componentAccessToken, componentAppId, authorizationCode);
        WeiXinAuthorizerInfo weiXinAuthorizerInfo = WeiXinUtils.apiGetAuthorizerInfo(componentAccessToken, componentAppId, weiXinAuthorizerToken.getAuthorizerAppId());
        weiXinAuthorizerInfo.setTenantId(BigInteger.valueOf(Long.valueOf(tenantId)));
        weiXinAuthorizerInfo.setCreateUserId(BigInteger.ONE);
        weiXinAuthorizerInfo.setLastUpdateUserId(BigInteger.ONE);
        DatabaseHelper.insert(weiXinAuthorizerInfo);
        return Constants.SUCCESS;
    }

    @RequestMapping(value = "/messageCallback/{appId}")
    @ResponseBody
    public String messageCallback(@PathVariable(value = "appId") String appId, HttpServletRequest httpServletRequest) throws IOException, DocumentException {
        InputStream inputStream = httpServletRequest.getInputStream();
        String requestBody = IOUtils.toString(inputStream);

        WeiXinOpenPlatformApplication weiXinOpenPlatformApplication = weiXinService.obtainWeiXinOpenPlatformApplication(appId);
        if (weiXinOpenPlatformApplication == null) {
            return Constants.SUCCESS;
        }
        String encodingAesKey = weiXinOpenPlatformApplication.getEncodingAesKey();

        String message = decrypt(requestBody, encodingAesKey);
        Map<String, String> xmlMap = XmlUtils.xmlStringToMap(message);

        KafkaUtils.send(Constants.WEI_XIN_MESSAGE_TOPIC, UUID.randomUUID().toString(), GsonUtils.toJson(xmlMap));

        return Constants.SUCCESS;
    }

    @RequestMapping(value = "/coreReceive")
    @ResponseBody
    public String coreReceive(HttpServletRequest httpServletRequest) throws IOException, DocumentException {
        String method = httpServletRequest.getMethod();
        String returnValue = null;
        if ("GET".equals(method)) {
            Map<String, String> requestParameters = ApplicationHandler.getRequestParameters(httpServletRequest);
            String timestamp = requestParameters.get("timestamp");
            String nonce = requestParameters.get("nonce");
            String token = "DearFangXiang";

            String[] array = {token, timestamp, nonce};
            Arrays.sort(array);

            String signature = requestParameters.get("signature");
            ValidateUtils.isTrue(signature.equals(DigestUtils.sha1Hex(StringUtils.join(array, ""))), "签名验证未通过！");

            returnValue = requestParameters.get("echostr");
        } else {
            String requestBody = IOUtils.toString(httpServletRequest.getInputStream());
            Map<String, String> bodyMap = XmlUtils.xmlStringToMap(requestBody);
            String msgType = bodyMap.get("MsgType");
            String event = bodyMap.get("Event");
            if ("event".equals(msgType) && "subscribe".equals(event)) {
                returnValue = Constants.SUCCESS;

                String openId = bodyMap.get("FromUserName");
                new Thread(() -> {
                    ThreadUtils.sleepSafe(2000);

                    Map<String, Object> messageBody = new HashMap<String, Object>();
                    messageBody.put("touser", openId);
                    messageBody.put("msgtype", "text");

                    Map<String, Object> text = new HashMap<String, Object>();
                    text.put("content", UUID.randomUUID().toString());
                    messageBody.put("text", text);

                    WeiXinUtils.sendCustomMessage("wx7f39242a4fd5bf0a", "dc3ba603115c02c8704cdeebb616bbfa", GsonUtils.toJson(messageBody));
                }).start();
            } else {
                returnValue = Constants.SUCCESS;
            }
        }

        return returnValue;
    }

    @RequestMapping(value = "/test")
    @ResponseBody
    public String test() throws IOException {
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        String openId = requestParameters.get("openId");
        List<Map<String, Object>> articles = new ArrayList<Map<String, Object>>();
        Map<String, Object> article = new HashMap<String, Object>();
        article.put("title", "消费通知");
        StringBuilder description = new StringBuilder("您的交易已支付成功\n");
        description.append("消费时间：").append(new SimpleDateFormat(Constants.DEFAULT_DATE_PATTERN).format(new Date())).append("\n");
        description.append("消费金额：").append("￥0.01元\n");
        description.append("优惠金额：").append("￥0.00元\n");
        description.append("实付金额：").append("￥0.01元\n");
        description.append("交易单号：").append("WX11211810080002");

        article.put("description", description.toString());
        article.put("url", "https://www.baidu.com");
        article.put("picurl", "http://image.smartpos.top/img/errorPage.png");
        articles.add(article);

        Map<String, Object> news = new HashMap<String, Object>();
        news.put("articles", articles);

        Map<String, Object> messageBody = new HashMap<String, Object>();
        messageBody.put("touser", openId);
        messageBody.put("msgtype", "news");
        messageBody.put("news", news);
        Map<String, Object> result = sendCustomMessage("wx6bb9ea76a4242455", "dbceac55f21809dc0f7cbbac99c4eca6", GsonUtils.toJson(messageBody));
        return Constants.SUCCESS;
    }

    public static WeiXinAccessToken obtainAccessToken(String appId, String secret) throws IOException {
        String weiXinAccessTokenJson = CacheUtils.hget(Constants.KEY_WEI_XIN_ACCESS_TOKENS, appId);
        boolean isRetrieveAccessToken = false;
        WeiXinAccessToken weiXinAccessToken = null;
        if (StringUtils.isNotBlank(weiXinAccessTokenJson)) {
            weiXinAccessToken = GsonUtils.fromJson(weiXinAccessTokenJson, WeiXinAccessToken.class);
            if ((System.currentTimeMillis() - weiXinAccessToken.getFetchTime().getTime()) / 1000 >= weiXinAccessToken.getExpiresIn()) {
                isRetrieveAccessToken = true;
            }
        } else {
            isRetrieveAccessToken = true;
        }
        if (isRetrieveAccessToken) {
            Map<String, String> obtainAccessTokenRequestParameters = new HashMap<String, String>();
            obtainAccessTokenRequestParameters.put("appid", appId);
            obtainAccessTokenRequestParameters.put("secret", secret);
            obtainAccessTokenRequestParameters.put("grant_type", "client_credential");
            String url = "https://api.weixin.qq.com/cgi-bin/token";
            WebResponse webResponse = WebUtils.doGetWithRequestParameters(url, obtainAccessTokenRequestParameters);

            JSONObject resultJsonObject = JSONObject.fromObject(webResponse.getResult());
            ValidateUtils.isTrue(!resultJsonObject.has("errcode"), resultJsonObject.optString("errmsg"));

            weiXinAccessToken = new WeiXinAccessToken();
            weiXinAccessToken.setAccessToken(resultJsonObject.getString("access_token"));
            weiXinAccessToken.setExpiresIn(resultJsonObject.getInt("expires_in"));
            weiXinAccessToken.setFetchTime(new Date());
            CacheUtils.hset(Constants.KEY_WEI_XIN_ACCESS_TOKENS, appId, GsonUtils.toJson(weiXinAccessToken));
        }
        return weiXinAccessToken;
    }

    public static Map<String, Object> sendCustomMessage(String appId, String secret, String message) throws IOException {
        WeiXinAccessToken weiXinAccessToken = obtainAccessToken(appId, secret);
        String accessToken = weiXinAccessToken.getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=" + accessToken;
        WebResponse webResponse = WebUtils.doPostWithRequestBody(url, message);
        String result = webResponse.getResult();
        Map<String, Object> resultMap = JacksonUtils.readValueAsMap(result, String.class, Object.class);
        int errcode = MapUtils.getIntValue(resultMap, "errcode");
        ValidateUtils.isTrue(errcode == 0, MapUtils.getString(resultMap, "errmsg"));
        return resultMap;
    }
}
