package com.plantcare.bot.repository;

import com.plantcare.bot.domain.PlantTemplateCareRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlantTemplateCareRuleRepository extends JpaRepository<PlantTemplateCareRule, Long> {

    List<PlantTemplateCareRule> findAllByTemplateId(Long templateId);
}
