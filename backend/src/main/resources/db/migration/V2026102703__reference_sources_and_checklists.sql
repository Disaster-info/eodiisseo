-- Reviewed official sources, abbreviated into app checklist items; never fabricate official boundaries.
-- Publishing a later template requires a new migration and an explicit active-version switch.
INSERT INTO disaster_type(disaster_type_id,code,name,active) VALUES
 ('10000000-0000-0000-0000-000000000001','EARTHQUAKE','지진',true),
 ('10000000-0000-0000-0000-000000000002','HEAVY_RAIN','호우',true),
 ('10000000-0000-0000-0000-000000000003','WILDFIRE','산불',true);

INSERT INTO data_source(source_id,name,source_kind,endpoint_key,collection_interval_minutes,active) VALUES
 ('20000000-0000-0000-0000-000000000001','기상청 초단기실황·예보','WEATHER','WEATHER_API',10,true),
 ('20000000-0000-0000-0000-000000000002','행정안전부 지진옥외대피장소','SHELTER','SAFETYDATA_EARTHQUAKE_SHELTER',NULL,true);

INSERT INTO official_resource(resource_id,agency_name,title,source_url,version_label,content_text,checked_at) VALUES
 ('30000000-0000-0000-0000-000000000001','행정안전부','지진 발생 시 국민행동요령',
  'https://www.korea.kr/multi/visualNewsView.do?newsId=148923236','검토본 1',
  '공식 지진 행동요령을 요약한 체크리스트. 원문과 현장 안내를 함께 확인한다.',CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000002','행정안전부','호우 국민행동요령',
  'https://www.safekorea.go.kr/safekorea-kor/acts/nacts/action-guide.do?category=heavyRain&menuSn=4','검토본 1',
  '공식 호우 행동요령을 요약한 체크리스트. 원문과 현장 안내를 함께 확인한다.',CURRENT_TIMESTAMP),
 ('30000000-0000-0000-0000-000000000003','행정안전부·산림청','산불 대피 국민행동요령',
  'https://www.mois.go.kr/frt/bbs/type002/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000530&nttId=124414','검토본 1',
  '공식 산불 대피 행동요령을 요약한 체크리스트. 원문과 현장 안내를 함께 확인한다.',CURRENT_TIMESTAMP);

INSERT INTO checklist_template(template_id,disaster_type_id,resource_id,title,version,active,published_at) VALUES
 ('40000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','지진 행동 점검',1,true,CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000002','30000000-0000-0000-0000-000000000002','호우 행동 점검',1,true,CURRENT_TIMESTAMP),
 ('40000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000003','30000000-0000-0000-0000-000000000003','산불 대피 점검',1,true,CURRENT_TIMESTAMP);

INSERT INTO checklist_item(item_id,template_id,item_order,content,active) VALUES
 ('50000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000001',1,'흔들리는 동안 튼튼한 탁자 밑에서 머리와 몸을 보호했나요?',true),
 ('50000000-0000-0000-0000-000000000002','40000000-0000-0000-0000-000000000001',2,'흔들림이 멎은 뒤 엘리베이터 대신 계단으로 이동했나요?',true),
 ('50000000-0000-0000-0000-000000000003','40000000-0000-0000-0000-000000000001',3,'머리를 보호하며 건물에서 떨어진 넓은 장소로 대피했나요?',true),
 ('50000000-0000-0000-0000-000000000004','40000000-0000-0000-0000-000000000002',1,'하천 주변과 침수 위험 구역에서 벗어났나요?',true),
 ('50000000-0000-0000-0000-000000000005','40000000-0000-0000-0000-000000000002',2,'물이 찬 도로나 지하차도로 진입하지 않았나요?',true),
 ('50000000-0000-0000-0000-000000000006','40000000-0000-0000-0000-000000000002',3,'기상 안내를 확인하고 위험한 외출을 피했나요?',true),
 ('50000000-0000-0000-0000-000000000007','40000000-0000-0000-0000-000000000003',1,'재난문자와 지역 방송에서 대피 안내를 확인했나요?',true),
 ('50000000-0000-0000-0000-000000000008','40000000-0000-0000-0000-000000000003',2,'대피 안내에 따라 산림과 불길에서 멀어지는 경로로 이동했나요?',true),
 ('50000000-0000-0000-0000-000000000009','40000000-0000-0000-0000-000000000003',3,'연기가 있는 곳에서는 젖은 수건으로 입과 코를 가리고 낮게 이동했나요?',true);
