-- Runs only inside the disposable database created by Prepare-AdministrativeAreas.ps1.
SET client_min_messages = warning;
CREATE TABLE received AS
SELECT f->'properties'->>'A1' AS code,
       ST_SetSRID(ST_GeomFromGeoJSON(f->'geometry'),4326) AS geom
FROM supplied_geojson, LATERAL jsonb_array_elements(document->'features') f;
ALTER TABLE received ADD PRIMARY KEY (code);

DO $$
BEGIN
  IF (SELECT count(*) FROM raw_sigungu) <> 256 OR
     (SELECT count(DISTINCT a1) FROM raw_sigungu) <> 256 OR
     (SELECT count(*) FROM received) <> 256 OR
     (SELECT count(DISTINCT ctprvn_cd) FROM source_sido) <> 16 OR
     EXISTS (SELECT left(a1,2) FROM raw_sigungu EXCEPT SELECT ctprvn_cd FROM source_sido) OR
     EXISTS (SELECT ctprvn_cd FROM source_sido EXCEPT SELECT left(a1,2) FROM raw_sigungu) OR
     EXISTS (SELECT 1 FROM raw_sigungu s LEFT JOIN received r ON r.code=s.a1
       WHERE s.a1 !~ '^[0-9]{5}$' OR r.code IS NULL OR s.geom IS NULL OR ST_IsEmpty(s.geom)
       OR r.geom IS NULL OR ST_IsEmpty(r.geom) OR s.a3 IS NULL OR s.a3::date > DATE '2026-11-09'
       OR s.a2 IS NULL OR (position(' ' in s.a2)=0 AND s.a1<>'36110')) THEN
    RAISE EXCEPTION 'Unexpected dataset coverage, geometry, metadata or hierarchy';
  END IF;
END $$;

CREATE TABLE boundaries AS
WITH inputs AS (
  SELECT a1 AS code, trim(a2) AS full_name, trim(a4) AS a4, a3::date AS attribute_date,
         s.geom AS native, ST_Transform(s.geom,4326) AS transformed, r.geom AS supplied
  FROM raw_sigungu s JOIN received r ON r.code=s.a1
)
SELECT *,
  ST_Multi(ST_CollectionExtract(ST_MakeValid(native,'method=linework'),3)) AS native_fixed,
  ST_Multi(ST_CollectionExtract(ST_MakeValid(transformed,'method=linework'),3)) AS transformed_fixed,
  ST_Multi(ST_CollectionExtract(ST_MakeValid(supplied,'method=linework'),3)) AS supplied_fixed,
  ST_Multi(ST_CollectionExtract(ST_MakeValid(transformed,'method=structure keepcollapsed=false'),3)) AS structure_fixed
FROM inputs;

CREATE TABLE chosen AS
SELECT code, full_name, left(code,2) AS parent, attribute_date,
       transformed_fixed AS geom FROM boundaries;
CREATE TABLE derived_sido AS
SELECT p.ctprvn_cd AS code, p.ctp_kor_nm AS name,max(c.attribute_date) AS attribute_date,
       ST_Multi(ST_UnaryUnion(ST_Collect(c.geom ORDER BY c.code))) AS geom
FROM source_sido p JOIN chosen c ON c.parent=p.ctprvn_cd
GROUP BY p.ctprvn_cd,p.ctp_kor_nm;

DO $$
BEGIN
 IF EXISTS (
   SELECT 1 FROM (SELECT geom FROM chosen UNION ALL SELECT geom FROM derived_sido) g
   WHERE geom IS NULL OR ST_IsEmpty(geom) OR NOT ST_IsValid(geom)
      OR GeometryType(geom) <> 'MULTIPOLYGON' OR ST_SRID(geom) <> 4326
      OR NOT ST_CoveredBy(geom,ST_MakeEnvelope(-180,-90,180,90,4326))
 ) THEN RAISE EXCEPTION 'Invalid final geometry'; END IF;
END $$;

\if :detailed_analysis
CREATE VIEW geometry_diagnostics AS
SELECT code,full_name,a4,a4 IS NOT DISTINCT FROM code AS a4_matches_a1,attribute_date,
       p.ctp_kor_nm AS province_reference_name,
       (starts_with(full_name,p.ctp_kor_nm || ' ') OR (code='36110' AND full_name=p.ctp_kor_nm)) AS province_name_matches,
       ST_IsValidReason(native) AS native_validity,ST_IsValidReason(transformed) AS transformed_validity,
       ST_IsValidReason(supplied) AS supplied_validity,
       ST_NPoints(native) AS native_vertices, ST_NPoints(native_fixed) AS native_fixed_vertices,
       ST_NumGeometries(native) AS native_parts, ST_NumGeometries(native_fixed) AS native_fixed_parts,
       ST_Area(native) AS raw_algebraic_area_m2, ST_Area(native_fixed) AS fixed_area_m2,
       ST_Dimension(ST_MakeValid(native,'method=linework')) AS repaired_dimension,
       GeometryType(ST_MakeValid(native,'method=linework')) AS linework_type,
       ST_Length(ST_CollectionExtract(ST_MakeValid(native,'method=linework'),2)) AS collapsed_line_m,
       ST_NumGeometries(ST_CollectionExtract(ST_MakeValid(native,'method=linework'),1)) AS collapsed_points,
       ST_IsValidReason(ST_Transform(native_fixed,4326)) AS native_repair_then_transform_validity,
       CASE WHEN ST_IsValid(ST_Transform(transformed_fixed,5186)) AND ST_IsValid(ST_Transform(structure_fixed,5186))
         THEN ST_Area(ST_SymDifference(ST_Transform(transformed_fixed,5186),ST_Transform(structure_fixed,5186))) END
         AS linework_structure_difference_m2,
       CASE WHEN ST_IsValid(native_fixed) AND ST_IsValid(ST_Transform(transformed_fixed,5186))
         THEN ST_Area(ST_SymDifference(native_fixed,ST_Transform(transformed_fixed,5186))) END
         AS repair_order_difference_m2,
       CASE WHEN ST_IsValid(ST_Transform(transformed_fixed,5186)) AND ST_IsValid(ST_Transform(supplied_fixed,5186))
         THEN ST_Area(ST_SymDifference(ST_Transform(transformed_fixed,5186),ST_Transform(supplied_fixed,5186))) END
         AS supplied_transform_difference_m2
FROM boundaries b JOIN chosen c USING (code,full_name,attribute_date)
JOIN source_sido p ON p.ctprvn_cd=c.parent;

-- Compare all methods in one projected metric CRS. Raw intersections exclude invalid inputs.
CREATE TABLE overlap_input AS
SELECT code, full_name, x.method, x.geom FROM boundaries
CROSS JOIN LATERAL (VALUES
 ('native_valid_only',CASE WHEN ST_IsValid(native) THEN native END),
 ('transformed_valid_only',CASE WHEN ST_IsValid(transformed) THEN ST_Transform(transformed,5186) END),
 ('supplied_valid_only',CASE WHEN ST_IsValid(supplied) THEN ST_Transform(supplied,5186) END),
 ('native_linework',native_fixed),
 ('transformed_linework',ST_Transform(transformed_fixed,5186)),
 ('supplied_linework',ST_Transform(supplied_fixed,5186)),
 ('transformed_structure',ST_Transform(structure_fixed,5186))
) x(method,geom)
WHERE x.geom IS NOT NULL AND NOT ST_IsEmpty(x.geom) AND ST_IsValid(x.geom);
CREATE INDEX ON overlap_input USING gist (geom);
CREATE TABLE overlap_pairs AS
SELECT a.method,a.code AS code_a,b.code AS code_b,a.full_name AS name_a,b.full_name AS name_b,
       ST_Area(o.geom) AS area_m2,ST_Area(o.geom)/least(ST_Area(a.geom),ST_Area(b.geom)) AS smaller_share,
       left(a.code,2)=left(b.code,2) AS same_sido,
       ST_NumGeometries(ST_CollectionExtract(o.geom,3)) AS polygon_parts,
       ST_Perimeter(o.geom) AS perimeter_m, ST_X(p.geom) AS longitude, ST_Y(p.geom) AS latitude,
       ST_AsText(ST_Envelope(ST_Transform(o.geom,4326))) AS bbox_4326
FROM overlap_input a JOIN overlap_input b ON a.method=b.method AND a.code<b.code AND a.geom && b.geom
CROSS JOIN LATERAL (SELECT ST_Intersection(a.geom,b.geom) AS geom OFFSET 0) o
CROSS JOIN LATERAL (SELECT ST_Transform(ST_PointOnSurface(ST_CollectionExtract(o.geom,3)),4326) AS geom OFFSET 0) p
WHERE ST_Area(o.geom)>0;
CREATE VIEW overlap_summary AS
SELECT method,count(*) AS positive,count(*) FILTER(WHERE area_m2>1) AS over_1,
       count(*) FILTER(WHERE area_m2>100) AS over_100,
       count(*) FILTER(WHERE area_m2>10000) AS over_10000,max(area_m2) AS max_m2
FROM overlap_pairs GROUP BY method;
\endif

CREATE VIEW import_rows AS
WITH areas AS (
 SELECT code,name,NULL::text AS parent,'SIDO' AS level,'VWORLD_SIG_DERIVED' AS provider,attribute_date,geom FROM derived_sido
 UNION ALL
 SELECT code,substring(full_name FROM position(' ' in full_name)+1),parent,'SIGUNGU','VWORLD_SIG',attribute_date,geom FROM chosen
)
SELECT code,ST_NPoints(areas.geom) AS vertices,ST_NumGeometries(areas.geom) AS parts,
       jsonb_build_object('regionCode',code,'name',name,'level',level,'parentRegionCode',parent,'active',true,
         'sourceUrl','https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=21',
         'sourceProvider',provider,'sourceAreaCode',code,'baseYear',2026,
         'sourceUpdatedAt',attribute_date::text || 'T00:00:00+09:00',
         'latitude',ST_Y(p.geom),'longitude',ST_X(p.geom),'boundary',ST_AsGeoJSON(areas.geom,16,0)::jsonb) AS area
FROM areas CROSS JOIN LATERAL (SELECT ST_PointOnSurface(areas.geom) AS geom OFFSET 0) p;
