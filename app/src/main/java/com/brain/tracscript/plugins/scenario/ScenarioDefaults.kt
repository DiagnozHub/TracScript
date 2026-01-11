package com.brain.tracscript.plugins.scenario

object ScenarioDefaults {
    // Тот же сценарий, что был зашит в runScenario
    const val DEFAULT_SCENARIO = """
    # Сценарий сканирования Камаза

    # закрыть Diagzone
    #CLOSE_APP com.diagzone.pro.v2
    KILL_APP_ROOT com.diagzone.pro.v2
    DELAY 3000
    
    # Запускаем Diagzone
    LAUNCH_APP com.diagzone.pro.v2
    DELAY 5000
    
    WAIT_TEXT Локальная диагностика
    CLICK_TEXT Локальная диагностика
    DELAY 3000
    
    WAIT_TEXT HD_OBD
    CLICK_TEXT HD_OBD
    DELAY 3000
    
    WAIT_TEXT Подтверждение
    CLICK_TEXT Подтверждение
    DELAY 40000
    
    WAIT_TEXT HD OBD
    CLICK_TEXT HD OBD
    DELAY 3000
    
    WAIT_TEXT System Automatic Search (Heavy Duty)
    CLICK_TEXT System Automatic Search (Heavy Duty)
    DELAY 50000
    
    # На окне с VIN нажимаем Назад
    BACK
    DELAY 3000
    
    WAIT_TEXT Health Report
    CLICK_TEXT Health Report
    DELAY 90000
    
    DELETE_FILE file=codes.json
    
    EXTRACT_TABLE_BY_ID id=com.diagzone.pro.v2:id/systemStateCodeList file=codes.json
    DELAY 3000
    
    SEND_WIALON_TABLE file=codes.json
    DELETE_FILE file=codes.json
    DELAY 3000
    """
}
