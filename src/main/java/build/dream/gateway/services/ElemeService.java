package build.dream.gateway.services;

import build.dream.common.utils.*;
import build.dream.gateway.constants.Constants;
import build.dream.gateway.mappers.TenantMapper;
import net.sf.json.JSONObject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.Validate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.concurrent.ListenableFuture;
import scala.Tuple2;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class ElemeService {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Transactional(readOnly = true)
    public String handleElemeCallback(String callbackRequestBody) throws IOException, ExecutionException, InterruptedException {
        JSONObject callbackRequestBodyJsonObject = JSONObject.fromObject(callbackRequestBody);
        Validate.isTrue(ElemeUtils.checkSignature(callbackRequestBodyJsonObject, ConfigurationUtils.getConfiguration(Constants.ELEME_APP_SECRET)), "签名校验未通过！");

        String uuid = DigestUtils.md5Hex(callbackRequestBodyJsonObject.getString("message"));
        String key = "eleme_callback_sign_" + uuid;
        boolean setnxSuccessful = CacheUtils.setnx(key, key);
        String handleResult = null;
        if (setnxSuccessful) {
            handleResult = handleElemeCallback(key, uuid, callbackRequestBodyJsonObject);
        } else {
            handleResult = Constants.ELEME_ORDER_CALLBACK_SUCCESS_RETURN_VALUE;
        }
        return handleResult;
    }

    private String handleElemeCallback(String key, String uuid, JSONObject callbackRequestBodyJsonObject) throws IOException, ExecutionException, InterruptedException {
        String handleResult = null;
        try {
            CacheUtils.expire(key, 1800, TimeUnit.SECONDS);
            BigInteger shopId = BigInteger.valueOf(callbackRequestBodyJsonObject.getLong("shopId"));

            Map<String, Object> tenantInfo = DatabaseHelper.callMapperMethod(TenantMapper.class, "obtainTenantInfo", new Tuple2<Class<?>, Object>(BigInteger.class, shopId));
            if (MapUtils.isEmpty(tenantInfo)) {
                handleResult = Constants.ELEME_ORDER_CALLBACK_SUCCESS_RETURN_VALUE;
            } else {
                String partitionCode = MapUtils.getString(tenantInfo, "partitionCode");
                Map<String, Object> elemeMessage = new HashMap<String, Object>();
                elemeMessage.put("uuid", uuid);
                elemeMessage.put("callbackRequestBody", callbackRequestBodyJsonObject);
                elemeMessage.put("count", 10);

                String topic = partitionCode + "_" + ConfigurationUtils.getConfiguration(Constants.ELEME_MESSAGE_TOPIC);
                ListenableFuture<SendResult<String, String>> listenableFuture = kafkaTemplate.send(topic, uuid, GsonUtils.toJson(elemeMessage));
                SendResult<String, String> sendResult = listenableFuture.get();
                ProducerRecord<String, String> producerRecord = sendResult.getProducerRecord();
                RecordMetadata recordMetadata = sendResult.getRecordMetadata();
                handleResult = Constants.ELEME_ORDER_CALLBACK_SUCCESS_RETURN_VALUE;
            }
        } catch (Exception e) {
            CacheUtils.delete(key);
            throw e;
        }
        return handleResult;
    }
}