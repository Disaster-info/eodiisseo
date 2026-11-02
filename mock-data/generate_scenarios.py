"""Generate synthetic values using only documented external response fields. No DB or secrets."""
from pathlib import Path
import json

ROOT=Path(__file__).resolve().parent
SCENARIOS=ROOT/'scenarios'

def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def safetydata(items):
    return {'header':{'resultCode':'00','resultMsg':'NORMAL SERVICE','errorMsg':None},
            'numOfRows':10,'pageNo':1,'totalCount':len(items),'body':items or None}

def kma(categories,x,y,forecast=False):
    items=[]
    for category,value in categories.items():
        item={'baseDate':'20261030','baseTime':'0730' if forecast else '0800','category':category,'nx':x,'ny':y}
        if forecast:item.update(fcstDate='20261030',fcstTime='0900',fcstValue=value)
        else:item['obsrValue']=value
        items.append(item)
    return {'response':{'header':{'resultCode':'00','resultMsg':'NORMAL_SERVICE'},
                       'body':{'dataType':'JSON','items':{'item':items},'pageNo':1,'numOfRows':1000,'totalCount':len(items)}}}

def main():
    cases=[
        ('earthquake','[TEST] 가상지역에서 흔들림 신고가 접수되었습니다. 관계기관 안내를 확인해 주세요.',
         '[TEST] 가상 지진 안내지역','2026-10-30 09:08:00',60,127,{'T1H':'18.2','PTY':'0'},'1'),
        ('heavy-rain','[TEST] 가상지역 호우 관련 안내입니다. 통제구간과 관계기관의 대피 안내를 확인해 주세요.',
         '[TEST] 가상 호우 안내지역','2026-10-30 09:05:00',98,76,{'T1H':'21.4','PTY':'1'},'4'),
        ('wildfire','[TEST] 가상지역 산불 관련 안내입니다. 주민께서는 발송기관의 후속 안내를 확인해 주세요.',
         '[TEST] 가상 산불 안내지역','2026-10-30 09:00:00',92,131,{'T1H':'23.1','PTY':'0'},'3')]
    files=[]
    for index,(name,text,region,created,x,y,weather,sky) in enumerate(cases,1):
        fixture={
            'disasterMessageResponse':safetydata([{'SN':9000000000+index,'CRT_DT':created,'MSG_CN':text,
                                                   'RCPTN_RGN_NM':region,'EMRG_STEP_NM':'안전안내'}]),
            'weatherObservationResponse':kma(weather,x,y),
            'weatherForecastResponse':kma({'SKY':sky},x,y,True)}
        write(SCENARIOS/(name+'.json'),fixture);files.append(name+'.json')

    # These facility fields are mapped by the existing DSSP-IF-10943 DTO.
    shelter=[{'ARCD':'1168000000','ACMDFCLTY_SN':900001,'VT_ACMDFCLTY_NM':'[TEST] 가상 운동장',
              'RN_DTL_ADRES':'[TEST] 가상 도로명주소','EQK_ACMDFCLTY_ADRES':'[TEST] 가상 지번주소',
              'LO':'127.0000','LA':'37.5000','VT_ACMD_PSBL_NMPR':250,'FCLTY_AR':1200.0,'USE_SE_CD':'1','TELNO':None},
             {'ARCD':'1168000000','ACMDFCLTY_SN':900002,'VT_ACMDFCLTY_NM':'[TEST] 수용인원 미제공 시설',
              'RN_DTL_ADRES':None,'EQK_ACMDFCLTY_ADRES':'[TEST] 가상 지번주소 2',
              'LO':'127.0040','LA':'37.5050','VT_ACMD_PSBL_NMPR':None,'FCLTY_AR':None,'USE_SE_CD':'1','TELNO':None}]
    write(SCENARIOS/'earthquake-shelters.json',safetydata(shelter))
    write(SCENARIOS/'earthquake-shelters-empty.json',safetydata([]))
    route={'type':'FeatureCollection','features':[
        {'type':'Feature','geometry':{'type':'Point','coordinates':[127.0,37.5]},
         'properties':{'totalDistance':680,'totalTime':540,'index':0,'pointIndex':0,'name':'[TEST] 출발지','description':'[TEST] 경로 표시용 출발점','pointType':'SP','turnType':200}},
        {'type':'Feature','geometry':{'type':'LineString','coordinates':[[127.0,37.5],[127.002,37.503],[127.004,37.505]]},
         'properties':{'index':1,'lineIndex':0,'name':'[TEST] 경로 표시용 구간','description':'[TEST] 가상 구간','distance':680,'time':540}},
        {'type':'Feature','geometry':{'type':'Point','coordinates':[127.004,37.505]},
         'properties':{'index':2,'pointIndex':1,'name':'[TEST] 도착지','description':'[TEST] 경로 표시용 도착점','pointType':'EP','turnType':201}}]}
    write(SCENARIOS/'tmap-pedestrian.json',route)
    write(SCENARIOS/'tmap-pedestrian-empty.json',{'type':'FeatureCollection','features':[]})
    write(SCENARIOS/'weather-no-data.json',{'response':{'header':{'resultCode':'03','resultMsg':'NODATA_ERROR'}}})
    write(SCENARIOS/'manifest.json',{
        'isTest':True,'schemaVersion':'2.0.0','fixtureValuesAreSynthetic':True,
        'scenarioFiles':files,
        'sharedResponseFiles':['earthquake-shelters.json','earthquake-shelters-empty.json','tmap-pedestrian.json','tmap-pedestrian-empty.json','weather-no-data.json'],
        'sources':[
            {'api':'DSSP-IF-00247','scope':'SN, CRT_DT, MSG_CN, RCPTN_RGN_NM, EMRG_STEP_NM',
             'reference':'https://www.safetydata.go.kr/disaster-data/view?dataSn=228',
             'verification':'공식 조회 필드와 프로젝트 외부 API 정리본 기준. 이 프로젝트에서 실응답 타입·코드값 검증 전.'},
            {'api':'getUltraSrtNcst / getUltraSrtFcst','scope':'T1H, PTY / SKY',
             'reference':'backend/src/main/java/com/disasterinfo/eodiisseo/weather/client/KmaWeatherProvider.java'},
            {'api':'DSSP-IF-10943','scope':'현재 EarthquakeShelterItem DTO 필드',
             'reference':'backend/src/main/java/com/disasterinfo/eodiisseo/integration/safetydata/EarthquakeShelterItem.java'},
            {'api':'TMAP 보행자 경로','scope':'FeatureCollection, geometry, totalDistance, totalTime, description 등',
             'reference':'backend/src/main/java/com/disasterinfo/eodiisseo/integration/tmap/TmapResponseDto.java'}],
        'limits':[
            'CRT_DT는 문자 생성시각이며 재난 발생시각이 아니다.',
            'RCPTN_RGN_NM은 수신지역이며 피해·영향 경계가 아니다.',
            'EMRG_STEP_NM은 문자 긴급단계이며 재난 심각도 등급이 아니다.',
            '회원·그룹·개인 GPS·체크리스트 완료·알림 발송·AI 결과·수집 상태는 제공하지 않는다.',
            '산불 피해면적·대응단계·진화율, 임의 재난 상태·종료시각·영향 폴리곤은 생성하지 않는다.',
            '지진 규모·진앙은 별도 기상청 지진 응답 계약을 검증하기 전에는 생성하지 않는다.',
            '초단기실황의 격자는 날씨 조회 지점이며 재난 위치가 아니다.',
            '지진 옥외대피장소를 호우·산불용 시설로 간주하지 않는다.',
            '시설 수용인원은 현재 남은 자리 수가 아니다.',
            '가상 TMAP 좌표·거리·시간은 응답 파싱용이며 실제 통행·재난 안전성 검증이 아니다.']})
    print('Wrote 3 minimal API scenarios and 5 shared API response fixtures. No SQL or internal records.')

if __name__=='__main__':main()
