package dev.granary.core.dao;

import dev.granary.core.model.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingDao extends JpaRepository<Setting, String> {
}
