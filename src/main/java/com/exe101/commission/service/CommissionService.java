package com.exe101.commission.service;

import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionAttachment;
import com.exe101.commission.model.CommissionMessage;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.FileUpload;
import com.exe101.file.model.FileContent;
import java.util.List;

public interface CommissionService {
    Commission request(Long userId, CommissionRequest request);

    List<Commission> listMine(Long userId);

    Commission get(Long userId, Long commissionId);

    List<CommissionMessage> listMessages(Long userId, Long commissionId);

    CommissionMessage sendMessage(Long userId, Long commissionId, String body);

    CommissionAttachment addAttachment(Long userId, Long commissionId, FileUpload upload);

    FileContent readAttachment(Long userId, Long commissionId, Long attachmentId);
}
