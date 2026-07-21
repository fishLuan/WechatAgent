package com.luanxv.pre.wechatAI.repository;

import com.luanxv.pre.wechatAI.model.UserVoicePreference;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserVoicePreferenceRepository extends MongoRepository<UserVoicePreference, String> {
}
