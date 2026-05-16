package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.AppleDeviceRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AppleDeviceRepository extends JpaRepository<AppleDeviceRegistration, AppleDeviceRegistration.PK> {
    List<AppleDeviceRegistration> findBySerialNumber(String serialNumber);
    List<AppleDeviceRegistration> findByDeviceLibraryIdentifier(String deviceId);
    void deleteByDeviceLibraryIdentifierAndSerialNumber(String deviceId, String serialNumber);
}