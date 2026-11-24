"""Localized store listings for the App Store and Google Play (source of truth: English in APPSTORE.md / PLAYSTORE.md).

Tokens in `desc`: {HEALTH} -> "Apple Health" / "Health Connect"; {EXTRAS} -> the locale's `extras_ios` / `extras_android`.
Limits: name 30, subtitle 30, promo 170, keywords 100 (App Store); short 80, desc 4000 (both).
Brand and product names (Ayuvo, Apple Health, Health Connect, Google Health, Open Food Facts, provider names) stay in English.
"""

URLS = "\n\nPrivacy: https://ayuvo-health.web.app/privacy\nSupport: https://ayuvo-health.web.app/support"

L = {}

L["de"] = dict(
    name="Ayuvo – KI-Gesundheitscoach",
    subtitle="Ernährung, Training, KI-Coach",
    promo="Ernährung, Training, Fasten, Wasser und deine Gesundheitsdaten in einer privaten App – mit KI-Coach, der mit deinem eigenen Schlüssel läuft. Kein Konto, keine Werbung.",
    keywords="kalorienzähler,makros,ernährung,barcode,fitness,gewicht,fasten,wasser,schlaf,medikamente,zyklus",
    short="Ernährung, Training, Befunde und Gesundheitsdaten – privat auf deinem Handy.",
    extras_ios="Widgets, eine Apple-Watch-App, Siri-Kurzbefehle und 18 Sprachen.",
    extras_android="Widgets, App-Verknüpfungen, optionale Google-Drive-Sicherung und 18 Sprachen.",
    desc="""Ayuvo ist dein privater Gesundheitsbegleiter für alles an einem Ort: Mahlzeiten, Training, Medikamente und Befunde, deine {HEALTH}-Daten in einem Hub und ein KI-Coach, der das Gesamtbild sieht – ohne Konto und ohne Ayuvo-Server.

ERNÄHRUNG
Fotografiere bis zu 10 Bilder einer Mahlzeit, scanne einen Barcode (Open Food Facts), sprich oder tippe sie ein. Prüfe Kalorien, Makros und über 30 Nährstoffe, bevor du speicherst. Persönliche Ziele, die du anpassen kannst.

TRAINING
Trainingstagebuch mit Sätzen, Wiederholungen, Gewicht und RPE, eine Bibliothek mit 1.300 Übungen und GPS-Aufzeichnung für Gehen, Laufen, Radfahren und Wandern.

GESUNDHEITSDATEN
Mit deiner Erlaubnis spiegelt Ayuvo die {HEALTH}-Daten, die du freigibst – Aktivität, Herz, Schlaf, Vitalwerte, Körpermaße, Zyklus und mehr – in einen lokalen Hub mit Diagrammen vom Tag bis zum Jahr.

BEFUNDE & MEDIKAMENTE
Laborberichte, Rezepte, Arztbriefe und Rechnungen an einem Ort. Der Text wird auf deinem Handy gelesen, Laborwerte werden über Berichte hinweg verfolgt. Medikamente mit Plänen, Erinnerungen und Einnahmeprotokoll.

ZYKLUS
Optionaler Periodentracker mit Kalender und Schätzungen (nur Schätzungen, keine Verhütung).

KAMERAMESSUNGEN
Finger- und Gesichtsscans schätzen Puls, Herzfrequenzvariabilität und Atemfrequenz – allgemeines Wohlbefinden, keine medizinische Messung.

KI-COACH
Nutze deinen eigenen Schlüssel (Google Gemini, OpenAI, Anthropic und viele mehr) oder ein Modell direkt auf dem Gerät. Gesundheitsdaten sieht der Coach nur, wenn du es erlaubst.

{EXTRAS}

DATENSCHUTZ
Kein Konto, keine Werbung, keine Analyse. Deine Daten werden auf diesem Gerät gespeichert und verlassen es nur, wenn du handelst.

Ayuvo ist kein Medizinprodukt und gibt keinen medizinischen Rat.""",
)

L["fr"] = dict(
    name="Ayuvo – Coach santé IA",
    subtitle="Nutrition, sport, coach IA",
    promo="Nutrition, sport, jeûne, eau et vos données de santé dans une app privée – avec un coach IA qui fonctionne avec votre propre clé. Sans compte, sans publicité.",
    keywords="calories,macros,nutrition,code-barres,fitness,poids,jeûne,eau,sommeil,médicaments,règles,régime",
    short="Nutrition, sport, dossiers médicaux et santé, en privé sur votre téléphone.",
    extras_ios="Widgets, app Apple Watch, raccourcis Siri et 18 langues.",
    extras_android="Widgets, raccourcis d'app, sauvegarde Google Drive facultative et 18 langues.",
    desc="""Ayuvo est un compagnon santé privé et complet : repas, entraînements, médicaments et dossiers médicaux, vos données {HEALTH} réunies en un seul endroit, et un coach IA qui voit l'ensemble – sans compte et sans serveur Ayuvo.

NUTRITION
Prenez jusqu'à 10 photos d'un repas, scannez un code-barres (Open Food Facts), dictez-le ou tapez-le. Vérifiez calories, macros et plus de 30 nutriments avant d'enregistrer. Objectifs personnalisés et modifiables.

SPORT
Journal d'entraînement avec séries, répétitions, poids et RPE, bibliothèque de 1 300 exercices et enregistrement GPS de la marche, de la course, du vélo et de la randonnée.

DONNÉES DE SANTÉ
Avec votre accord, Ayuvo reprend les données {HEALTH} que vous autorisez – activité, cœur, sommeil, signes vitaux, mesures corporelles, cycle et plus – dans un espace local avec des graphiques du jour à l'année.

DOSSIERS & MÉDICAMENTS
Analyses, ordonnances, comptes rendus et factures au même endroit. Le texte est lu sur votre téléphone et les résultats sont suivis d'un rapport à l'autre. Médicaments avec horaires, rappels et journal des prises.

CYCLE
Suivi des règles facultatif avec calendrier et estimations (estimations uniquement, pas une contraception).

MESURES PAR CAMÉRA
Des scans du doigt et du visage estiment le pouls, la variabilité cardiaque et la fréquence respiratoire – bien-être général, pas une mesure médicale.

COACH IA
Utilisez votre propre clé (Google Gemini, OpenAI, Anthropic et bien d'autres) ou un modèle sur l'appareil. Le coach ne voit vos données de santé que si vous l'autorisez.

{EXTRAS}

CONFIDENTIALITÉ
Sans compte, sans publicité, sans statistiques. Vos données sont stockées sur cet appareil et n'en sortent que lorsque vous agissez.

Ayuvo n'est pas un dispositif médical et ne donne pas de conseils médicaux.""",
)

L["es"] = dict(
    name="Ayuvo – Coach de salud IA",
    subtitle="Nutrición, ejercicio, coach IA",
    promo="Nutrición, ejercicio, ayuno, agua y tus datos de salud en una app privada, con un coach de IA que funciona con tu propia clave. Sin cuenta y sin anuncios.",
    keywords="calorías,macros,nutrición,código,fitness,peso,ayuno,agua,sueño,medicamentos,periodo,dieta,gym",
    short="Nutrición, ejercicio, informes médicos y datos de salud, privados en tu móvil.",
    extras_ios="Widgets, app para Apple Watch, atajos de Siri y 18 idiomas.",
    extras_android="Widgets, accesos directos, copia opcional en Google Drive y 18 idiomas.",
    desc="""Ayuvo es un compañero de salud privado y completo: comidas, entrenamientos, medicamentos e informes médicos, tus datos de {HEALTH} en un solo lugar y un coach de IA que ve el panorama completo, sin cuenta y sin servidores de Ayuvo.

NUTRICIÓN
Haz hasta 10 fotos de una comida, escanea un código de barras (Open Food Facts), díctala o escríbela. Revisa calorías, macros y más de 30 nutrientes antes de guardar. Objetivos personalizados que puedes ajustar.

EJERCICIO
Diario de entrenamiento con series, repeticiones, peso y RPE, una biblioteca de 1.300 ejercicios y registro GPS para caminar, correr, montar en bici y hacer senderismo.

DATOS DE SALUD
Con tu permiso, Ayuvo refleja los datos de {HEALTH} que autorices – actividad, corazón, sueño, constantes, medidas corporales, ciclo y más – en un espacio local con gráficos del día al año.

INFORMES Y MEDICAMENTOS
Análisis, recetas, informes y facturas en un solo lugar. El texto se lee en tu móvil y los resultados se siguen de un informe a otro. Medicamentos con horarios, recordatorios y registro de tomas.

CICLO
Seguimiento opcional del periodo con calendario y estimaciones (solo estimaciones, no es un anticonceptivo).

MEDICIONES CON CÁMARA
Escaneos del dedo y la cara estiman pulso, variabilidad cardiaca y frecuencia respiratoria: bienestar general, no una medición médica.

COACH DE IA
Usa tu propia clave (Google Gemini, OpenAI, Anthropic y muchos más) o un modelo en el dispositivo. El coach solo ve tus datos de salud si tú lo permites.

{EXTRAS}

PRIVACIDAD
Sin cuenta, sin anuncios, sin analíticas. Tus datos se guardan en este dispositivo y solo salen de él cuando tú lo decides.

Ayuvo no es un dispositivo médico y no ofrece consejo médico.""",
)

L["it"] = dict(
    name="Ayuvo – Coach salute IA",
    subtitle="Nutrizione, allenamento, IA",
    promo="Nutrizione, allenamento, digiuno, acqua e i tuoi dati sulla salute in un'app privata, con un coach IA che usa la tua chiave. Nessun account, nessuna pubblicità.",
    keywords="calorie,macro,nutrizione,codice,fitness,peso,digiuno,acqua,sonno,farmaci,ciclo,dieta,palestra",
    short="Nutrizione, allenamento, referti e dati sulla salute, privati sul tuo telefono.",
    extras_ios="Widget, app per Apple Watch, Comandi Siri e 18 lingue.",
    extras_android="Widget, scorciatoie, backup facoltativo su Google Drive e 18 lingue.",
    desc="""Ayuvo è un compagno per la salute privato e completo: pasti, allenamenti, farmaci e referti, i tuoi dati di {HEALTH} in un unico posto e un coach IA che vede il quadro completo, senza account e senza server Ayuvo.

NUTRIZIONE
Scatta fino a 10 foto di un pasto, scansiona un codice a barre (Open Food Facts), dettalo o scrivilo. Controlla calorie, macro e oltre 30 nutrienti prima di salvare. Obiettivi personalizzati e modificabili.

ALLENAMENTO
Diario con serie, ripetizioni, peso e RPE, una libreria di 1.300 esercizi e registrazione GPS di camminate, corse, uscite in bici ed escursioni.

DATI SULLA SALUTE
Con il tuo permesso, Ayuvo riporta i dati di {HEALTH} che autorizzi – attività, cuore, sonno, parametri vitali, misure corporee, ciclo e altro – in uno spazio locale con grafici dal giorno all'anno.

REFERTI E FARMACI
Analisi, ricette, lettere del medico e fatture in un unico posto. Il testo viene letto sul telefono e i valori sono seguiti da un referto all'altro. Farmaci con orari, promemoria e registro delle assunzioni.

CICLO
Monitoraggio facoltativo del ciclo con calendario e stime (solo stime, non è un contraccettivo).

MISURE CON LA FOTOCAMERA
Scansioni del dito e del viso stimano battito, variabilità cardiaca e frequenza respiratoria: benessere generale, non una misura medica.

COACH IA
Usa la tua chiave (Google Gemini, OpenAI, Anthropic e molti altri) o un modello sul dispositivo. Il coach vede i dati sulla salute solo se lo consenti.

{EXTRAS}

PRIVACY
Nessun account, nessuna pubblicità, nessuna analisi. I tuoi dati restano salvati su questo dispositivo e ne escono solo quando lo decidi tu.

Ayuvo non è un dispositivo medico e non fornisce consigli medici.""",
)

L["pt-BR"] = dict(
    name="Ayuvo – Coach de saúde IA",
    subtitle="Nutrição, treino, coach de IA",
    promo="Nutrição, treinos, jejum, água e seus dados de saúde em um app privado, com um coach de IA que usa a sua própria chave. Sem conta e sem anúncios.",
    keywords="calorias,macros,nutrição,código,fitness,peso,jejum,água,sono,remédios,menstruação,dieta,academia",
    short="Nutrição, treinos, exames e dados de saúde, com privacidade no seu celular.",
    extras_ios="Widgets, app para Apple Watch, Atalhos da Siri e 18 idiomas.",
    extras_android="Widgets, atalhos do app, backup opcional no Google Drive e 18 idiomas.",
    desc="""O Ayuvo é um companheiro de saúde privado e completo: refeições, treinos, remédios e exames, seus dados do {HEALTH} em um só lugar e um coach de IA que vê o quadro todo, sem conta e sem servidores do Ayuvo.

NUTRIÇÃO
Tire até 10 fotos de uma refeição, escaneie um código de barras (Open Food Facts), fale ou digite. Confira calorias, macros e mais de 30 nutrientes antes de salvar. Metas personalizadas que você pode ajustar.

TREINOS
Diário com séries, repetições, peso e RPE, uma biblioteca de 1.300 exercícios e registro por GPS de caminhadas, corridas, pedaladas e trilhas.

DADOS DE SAÚDE
Com sua permissão, o Ayuvo espelha os dados do {HEALTH} que você liberar – atividade, coração, sono, sinais vitais, medidas corporais, ciclo e mais – em um espaço local com gráficos do dia ao ano.

EXAMES E REMÉDIOS
Exames, receitas, relatórios médicos e contas em um só lugar. O texto é lido no seu celular e os resultados são acompanhados de um exame para outro. Remédios com horários, lembretes e registro de doses.

CICLO
Acompanhamento menstrual opcional com calendário e estimativas (apenas estimativas, não é método contraceptivo).

MEDIÇÕES PELA CÂMERA
Leituras do dedo e do rosto estimam pulso, variabilidade da frequência cardíaca e frequência respiratória: bem-estar geral, não uma medição médica.

COACH DE IA
Use a sua chave (Google Gemini, OpenAI, Anthropic e muitos outros) ou um modelo no próprio aparelho. O coach só vê seus dados de saúde se você permitir.

{EXTRAS}

PRIVACIDADE
Sem conta, sem anúncios, sem análises. Seus dados ficam salvos neste aparelho e só saem dele quando você decide.

O Ayuvo não é um dispositivo médico e não oferece orientação médica.""",
)

L["nl"] = dict(
    name="Ayuvo – AI-gezondheidscoach",
    subtitle="Voeding, training, AI-coach",
    promo="Voeding, training, vasten, water en je gezondheidsgegevens in één privé-app, met een AI-coach die werkt met je eigen sleutel. Geen account, geen advertenties.",
    keywords="calorieën,macro's,voeding,barcode,fitness,gewicht,vasten,water,slaap,medicijnen,menstruatie,dieet",
    short="Voeding, training, medische verslagen en gezondheidsdata, privé op je telefoon.",
    extras_ios="Widgets, een Apple Watch-app, Siri-opdrachten en 18 talen.",
    extras_android="Widgets, app-snelkoppelingen, optionele Google Drive-back-up en 18 talen.",
    desc="""Ayuvo is een privé gezondheidsmaatje voor alles op één plek: maaltijden, trainingen, medicijnen en medische verslagen, je {HEALTH}-gegevens in één overzicht en een AI-coach die het hele plaatje ziet – zonder account en zonder Ayuvo-servers.

VOEDING
Maak tot 10 foto's van een maaltijd, scan een barcode (Open Food Facts), spreek of typ hem in. Controleer calorieën, macro's en meer dan 30 voedingsstoffen voordat je opslaat. Persoonlijke doelen die je kunt aanpassen.

TRAINING
Trainingsdagboek met sets, herhalingen, gewicht en RPE, een bibliotheek met 1.300 oefeningen en GPS-registratie van wandelen, hardlopen, fietsen en hiken.

GEZONDHEIDSGEGEVENS
Met je toestemming haalt Ayuvo de {HEALTH}-gegevens die je deelt – activiteit, hart, slaap, vitale waarden, lichaamsmaten, cyclus en meer – naar een lokaal overzicht met grafieken van dag tot jaar.

VERSLAGEN & MEDICIJNEN
Labuitslagen, recepten, brieven en rekeningen op één plek. De tekst wordt op je telefoon gelezen en waarden worden over verslagen heen gevolgd. Medicijnen met schema's, herinneringen en een innamelogboek.

CYCLUS
Optionele menstruatietracker met kalender en schattingen (alleen schattingen, geen anticonceptie).

CAMERAMETINGEN
Vinger- en gezichtsscans schatten hartslag, hartslagvariabiliteit en ademhaling – algemeen welzijn, geen medische meting.

AI-COACH
Gebruik je eigen sleutel (Google Gemini, OpenAI, Anthropic en vele andere) of een model op het apparaat. De coach ziet je gezondheidsgegevens alleen als jij dat toestaat.

{EXTRAS}

PRIVACY
Geen account, geen advertenties, geen analyse. Je gegevens worden op dit apparaat bewaard en verlaten het alleen als jij dat doet.

Ayuvo is geen medisch hulpmiddel en geeft geen medisch advies.""",
)

L["pl"] = dict(
    name="Ayuvo – Asystent zdrowia AI",
    subtitle="Dieta, trening, coach AI",
    promo="Dieta, trening, post, woda i Twoje dane zdrowotne w jednej prywatnej aplikacji – z coachem AI działającym na Twoim własnym kluczu. Bez konta i bez reklam.",
    keywords="kalorie,makro,dieta,kod kreskowy,fitness,waga,post,woda,sen,leki,okres,siłownia,odżywianie",
    short="Dieta, trening, wyniki badań i dane zdrowotne – prywatnie na Twoim telefonie.",
    extras_ios="Widżety, aplikacja na Apple Watch, skróty Siri i 18 języków.",
    extras_android="Widżety, skróty aplikacji, opcjonalna kopia na Dysku Google i 18 języków.",
    desc="""Ayuvo to prywatny towarzysz zdrowia, w którym wszystko jest w jednym miejscu: posiłki, treningi, leki i dokumentacja medyczna, Twoje dane z {HEALTH} w jednym centrum oraz coach AI, który widzi pełny obraz – bez konta i bez serwerów Ayuvo.

DIETA
Zrób do 10 zdjęć posiłku, zeskanuj kod kreskowy (Open Food Facts), podyktuj go lub wpisz. Sprawdź kalorie, makroskładniki i ponad 30 składników odżywczych przed zapisaniem. Osobiste cele, które możesz zmienić.

TRENING
Dziennik treningowy z seriami, powtórzeniami, ciężarem i RPE, biblioteka 1300 ćwiczeń oraz zapis GPS spacerów, biegów, jazdy na rowerze i wędrówek.

DANE ZDROWOTNE
Za Twoją zgodą Ayuvo przenosi udostępnione dane z {HEALTH} – aktywność, serce, sen, parametry życiowe, wymiary ciała, cykl i inne – do lokalnego centrum z wykresami od dnia do roku.

BADANIA I LEKI
Wyniki badań, recepty, wypisy i rachunki w jednym miejscu. Tekst jest odczytywany na telefonie, a wyniki śledzone między badaniami. Leki z harmonogramem, przypomnieniami i dziennikiem dawek.

CYKL
Opcjonalny kalendarz miesiączkowy z szacunkami (tylko szacunki, nie jest to antykoncepcja).

POMIARY KAMERĄ
Skan palca i twarzy szacuje tętno, zmienność rytmu serca i częstość oddechu – ogólne samopoczucie, nie pomiar medyczny.

COACH AI
Użyj własnego klucza (Google Gemini, OpenAI, Anthropic i wiele innych) lub modelu na urządzeniu. Coach widzi dane zdrowotne tylko wtedy, gdy na to pozwolisz.

{EXTRAS}

PRYWATNOŚĆ
Bez konta, bez reklam, bez analityki. Twoje dane są przechowywane na tym urządzeniu i opuszczają je tylko wtedy, gdy Ty tak zdecydujesz.

Ayuvo nie jest wyrobem medycznym i nie udziela porad medycznych.""",
)

L["cs"] = dict(
    name="Ayuvo – AI zdravotní kouč",
    subtitle="Výživa, trénink, AI kouč",
    promo="Výživa, trénink, půst, voda a vaše zdravotní data v jedné soukromé aplikaci – s AI koučem, který běží na vašem vlastním klíči. Bez účtu a bez reklam.",
    keywords="kalorie,makra,výživa,čárový kód,fitness,váha,půst,voda,spánek,léky,menstruace,dieta,posilovna",
    short="Výživa, trénink, lékařské zprávy a zdravotní data – soukromě ve vašem telefonu.",
    extras_ios="Widgety, aplikace pro Apple Watch, zkratky Siri a 18 jazyků.",
    extras_android="Widgety, zkratky aplikace, volitelná záloha na Disk Google a 18 jazyků.",
    desc="""Ayuvo je soukromý zdravotní společník pro všechno na jednom místě: jídlo, tréninky, léky a lékařské zprávy, vaše data z {HEALTH} v jednom přehledu a AI kouč, který vidí celý obraz – bez účtu a bez serverů Ayuvo.

VÝŽIVA
Vyfoťte až 10 snímků jídla, naskenujte čárový kód (Open Food Facts), nadiktujte nebo napište. Zkontrolujte kalorie, makra a více než 30 živin, než uložíte. Osobní cíle, které můžete upravit.

TRÉNINK
Tréninkový deník se sériemi, opakováními, váhou a RPE, knihovna 1 300 cviků a GPS záznam chůze, běhu, jízdy na kole a turistiky.

ZDRAVOTNÍ DATA
S vaším svolením Ayuvo převezme data z {HEALTH}, která povolíte – aktivitu, srdce, spánek, vitální funkce, tělesné míry, cyklus a další – do místního přehledu s grafy od dne po rok.

ZPRÁVY A LÉKY
Laboratorní výsledky, recepty, lékařské zprávy a účty na jednom místě. Text se čte v telefonu a hodnoty se sledují napříč zprávami. Léky s rozpisem, připomínkami a záznamem dávek.

CYKLUS
Volitelné sledování menstruace s kalendářem a odhady (pouze odhady, nejde o antikoncepci).

MĚŘENÍ KAMEROU
Sken prstu a obličeje odhaduje tep, variabilitu srdeční frekvence a dechovou frekvenci – obecná pohoda, nikoli lékařské měření.

AI KOUČ
Použijte vlastní klíč (Google Gemini, OpenAI, Anthropic a mnoho dalších) nebo model přímo v zařízení. Kouč vidí zdravotní data jen tehdy, když to povolíte.

{EXTRAS}

SOUKROMÍ
Bez účtu, bez reklam, bez analytiky. Vaše data jsou uložena v tomto zařízení a opouštějí ho jen tehdy, když se tak rozhodnete.

Ayuvo není zdravotnický prostředek a neposkytuje lékařské rady.""",
)

L["ro"] = dict(
    name="Ayuvo – Coach de sănătate AI",
    subtitle="Nutriție, antrenament, AI",
    promo="Nutriție, antrenamente, post, apă și datele tale de sănătate într-o aplicație privată, cu un coach AI care folosește propria ta cheie. Fără cont, fără reclame.",
    keywords="calorii,macro,nutriție,cod de bare,fitness,greutate,post,apă,somn,medicamente,ciclu,dietă,sală",
    short="Nutriție, antrenamente, analize și date de sănătate, private pe telefonul tău.",
    extras_ios="Widgeturi, aplicație pentru Apple Watch, comenzi rapide Siri și 18 limbi.",
    extras_android="Widgeturi, comenzi rapide, backup opțional în Google Drive și 18 limbi.",
    desc="""Ayuvo este un însoțitor de sănătate privat și complet: mese, antrenamente, medicamente și documente medicale, datele tale din {HEALTH} într-un singur loc și un coach AI care vede imaginea completă – fără cont și fără servere Ayuvo.

NUTRIȚIE
Fă până la 10 fotografii ale unei mese, scanează un cod de bare (Open Food Facts), dictează sau scrie. Verifică caloriile, macronutrienții și peste 30 de nutrienți înainte de a salva. Obiective personale pe care le poți ajusta.

ANTRENAMENT
Jurnal cu serii, repetări, greutate și RPE, o bibliotecă de 1.300 de exerciții și înregistrare GPS pentru mers, alergare, ciclism și drumeții.

DATE DE SĂNĂTATE
Cu permisiunea ta, Ayuvo preia datele din {HEALTH} pe care le permiți – activitate, inimă, somn, semne vitale, măsurători corporale, ciclu și altele – într-un spațiu local cu grafice de la o zi la un an.

DOCUMENTE ȘI MEDICAMENTE
Analize, rețete, scrisori medicale și facturi într-un singur loc. Textul este citit pe telefon, iar valorile sunt urmărite de la un document la altul. Medicamente cu program, mementouri și jurnal al dozelor.

CICLU
Calendar menstrual opțional cu estimări (doar estimări, nu este o metodă contraceptivă).

MĂSURĂTORI CU CAMERA
Scanări ale degetului și feței estimează pulsul, variabilitatea ritmului cardiac și frecvența respiratorie – stare generală de bine, nu o măsurătoare medicală.

COACH AI
Folosește propria cheie (Google Gemini, OpenAI, Anthropic și multe altele) sau un model pe dispozitiv. Coachul vede datele de sănătate doar dacă îi permiți.

{EXTRAS}

CONFIDENȚIALITATE
Fără cont, fără reclame, fără analize. Datele tale sunt stocate pe acest dispozitiv și îl părăsesc doar când decizi tu.

Ayuvo nu este un dispozitiv medical și nu oferă sfaturi medicale.""",
)

L["ru"] = dict(
    name="Ayuvo – ИИ-помощник здоровья",
    subtitle="Питание, тренировки, ИИ-коуч",
    promo="Питание, тренировки, голодание, вода и данные о здоровье в одном приватном приложении – с ИИ-коучем на вашем собственном ключе. Без аккаунта и без рекламы.",
    keywords="калории,бжу,питание,штрихкод,фитнес,вес,голодание,вода,сон,лекарства,цикл,диета,спортзал",
    short="Питание, тренировки, анализы и данные о здоровье – приватно на вашем телефоне.",
    extras_ios="Виджеты, приложение для Apple Watch, быстрые команды Siri и 18 языков.",
    extras_android="Виджеты, ярлыки приложения, резервная копия в Google Диске по желанию и 18 языков.",
    desc="""Ayuvo – приватный помощник для здоровья, где всё в одном месте: питание, тренировки, лекарства и медицинские документы, данные из {HEALTH} в едином центре и ИИ-коуч, который видит полную картину. Без аккаунта и без серверов Ayuvo.

ПИТАНИЕ
Сфотографируйте блюдо (до 10 снимков), отсканируйте штрихкод (Open Food Facts), продиктуйте или введите текстом. Проверьте калории, БЖУ и более 30 нутриентов перед сохранением. Личные цели, которые можно изменить.

ТРЕНИРОВКИ
Дневник с подходами, повторами, весом и RPE, библиотека из 1300 упражнений и запись прогулок, пробежек, велопоездок и походов по GPS.

ДАННЫЕ О ЗДОРОВЬЕ
С вашего разрешения Ayuvo переносит данные из {HEALTH}, которые вы откроете, – активность, сердце, сон, показатели, замеры тела, цикл и другое – в локальный центр с графиками от дня до года.

ДОКУМЕНТЫ И ЛЕКАРСТВА
Анализы, рецепты, выписки и счета в одном месте. Текст распознаётся на телефоне, а показатели отслеживаются от анализа к анализу. Лекарства с расписанием, напоминаниями и журналом приёма.

ЦИКЛ
Необязательный трекер менструаций с календарём и прогнозами (только прогнозы, не контрацепция).

ИЗМЕРЕНИЯ КАМЕРОЙ
Сканирование пальца и лица оценивает пульс, вариабельность сердечного ритма и частоту дыхания – для общего самочувствия, не медицинское измерение.

ИИ-КОУЧ
Используйте свой ключ (Google Gemini, OpenAI, Anthropic и многие другие) или модель прямо на устройстве. Коуч видит данные о здоровье, только если вы это разрешите.

{EXTRAS}

КОНФИДЕНЦИАЛЬНОСТЬ
Без аккаунта, рекламы и аналитики. Данные хранятся на этом устройстве и покидают его только по вашему действию.

Ayuvo не является медицинским изделием и не даёт медицинских советов.""",
)

L["uk"] = dict(
    name="Ayuvo – ШІ-помічник здоров'я",
    subtitle="Харчування, тренування, ШІ",
    promo="Харчування, тренування, голодування, вода та дані про здоров'я в одному приватному застосунку – з ШІ-коучем на вашому власному ключі. Без акаунта й без реклами.",
    keywords="калорії,бжв,харчування,штрихкод,фітнес,вага,голодування,вода,сон,ліки,цикл,дієта,спортзал",
    short="Харчування, тренування, аналізи та дані про здоров'я – приватно на телефоні.",
    extras_ios="Віджети, застосунок для Apple Watch, швидкі команди Siri та 18 мов.",
    extras_android="Віджети, ярлики застосунку, резервна копія в Google Диску за бажанням і 18 мов.",
    desc="""Ayuvo – приватний помічник для здоров'я, де все в одному місці: харчування, тренування, ліки та медичні документи, дані з {HEALTH} в єдиному центрі та ШІ-коуч, який бачить повну картину. Без акаунта й без серверів Ayuvo.

ХАРЧУВАННЯ
Сфотографуйте страву (до 10 знімків), відскануйте штрихкод (Open Food Facts), продиктуйте або введіть текстом. Перевірте калорії, БЖВ і понад 30 нутрієнтів перед збереженням. Особисті цілі, які можна змінити.

ТРЕНУВАННЯ
Щоденник із підходами, повторами, вагою та RPE, бібліотека з 1300 вправ і запис прогулянок, пробіжок, велопоїздок і походів за GPS.

ДАНІ ПРО ЗДОРОВ'Я
З вашого дозволу Ayuvo переносить дані з {HEALTH}, які ви відкриєте, – активність, серце, сон, показники, заміри тіла, цикл та інше – до локального центру з графіками від дня до року.

ДОКУМЕНТИ ТА ЛІКИ
Аналізи, рецепти, виписки та рахунки в одному місці. Текст розпізнається на телефоні, а показники відстежуються від аналізу до аналізу. Ліки з розкладом, нагадуваннями та журналом прийому.

ЦИКЛ
Необов'язковий трекер менструацій із календарем і прогнозами (лише прогнози, не контрацепція).

ВИМІРЮВАННЯ КАМЕРОЮ
Сканування пальця та обличчя оцінює пульс, варіабельність серцевого ритму та частоту дихання – для загального самопочуття, не медичне вимірювання.

ШІ-КОУЧ
Використовуйте власний ключ (Google Gemini, OpenAI, Anthropic та багато інших) або модель просто на пристрої. Коуч бачить дані про здоров'я, лише якщо ви це дозволите.

{EXTRAS}

КОНФІДЕНЦІЙНІСТЬ
Без акаунта, реклами й аналітики. Дані зберігаються на цьому пристрої й залишають його лише за вашою дією.

Ayuvo не є медичним виробом і не надає медичних порад.""",
)

L["ar"] = dict(
    name="Ayuvo – مساعد صحي بالذكاء",
    subtitle="تغذية وتمارين ومدرب ذكي",
    promo="التغذية والتمارين والصيام والماء وبياناتك الصحية في تطبيق خاص واحد، مع مدرب ذكاء اصطناعي يعمل بمفتاحك الخاص. بلا حساب وبلا إعلانات.",
    keywords="سعرات,تغذية,باركود,لياقة,وزن,صيام,ماء,نوم,أدوية,دورة,حمية,تمارين,رياضة,صحة",
    short="التغذية والتمارين والتقارير الطبية وبياناتك الصحية، بخصوصية على هاتفك.",
    extras_ios="أدوات، وتطبيق لساعة Apple Watch، واختصارات Siri، و18 لغة.",
    extras_android="أدوات، واختصارات للتطبيق، ونسخ احتياطي اختياري على Google Drive، و18 لغة.",
    desc="""Ayuvo رفيق صحي خاص يجمع كل شيء في مكان واحد: الوجبات والتمارين والأدوية والتقارير الطبية، وبياناتك من {HEALTH} في مركز واحد، ومدرب ذكاء اصطناعي يرى الصورة كاملة، بلا حساب وبلا خوادم لـ Ayuvo.

التغذية
التقط حتى 10 صور لوجبتك، أو امسح الباركود (Open Food Facts)، أو أملِها أو اكتبها. راجع السعرات والعناصر الكبرى وأكثر من 30 عنصرًا غذائيًا قبل الحفظ. أهداف شخصية يمكنك تعديلها.

التمارين
سجل تمارين بالمجموعات والتكرارات والوزن ومستوى الجهد، ومكتبة تضم 1300 تمرين، وتسجيل المشي والجري وركوب الدراجة والتنزه عبر GPS.

البيانات الصحية
بإذنك، ينقل Ayuvo بيانات {HEALTH} التي تسمح بها، مثل النشاط والقلب والنوم والعلامات الحيوية وقياسات الجسم والدورة وغيرها، إلى مركز محلي برسوم بيانية من يوم إلى سنة.

التقارير والأدوية
التحاليل والوصفات والتقارير الطبية والفواتير في مكان واحد. يُقرأ النص على هاتفك وتُتابع النتائج من تقرير إلى آخر. أدوية بمواعيد وتذكيرات وسجل للجرعات.

الدورة الشهرية
متتبع اختياري للدورة مع تقويم وتقديرات (تقديرات فقط، وليس وسيلة لمنع الحمل).

قياسات بالكاميرا
مسح الإصبع والوجه يقدّر النبض وتقلب معدل ضربات القلب ومعدل التنفس، للعافية العامة وليس قياسًا طبيًا.

مدرب الذكاء الاصطناعي
استخدم مفتاحك الخاص (Google Gemini وOpenAI وAnthropic وغيرها) أو نموذجًا على الجهاز. لا يرى المدرب بياناتك الصحية إلا إذا سمحت بذلك.

{EXTRAS}

الخصوصية
بلا حساب ولا إعلانات ولا تحليلات. تُخزَّن بياناتك على هذا الجهاز ولا تغادره إلا بإجراء منك.

Ayuvo ليس جهازًا طبيًا ولا يقدم نصيحة طبية.""",
)

L["hi"] = dict(
    name="Ayuvo – AI हेल्थ साथी",
    subtitle="पोषण, वर्कआउट, AI कोच",
    promo="पोषण, वर्कआउट, उपवास, पानी और आपका हेल्थ डेटा एक निजी ऐप में – AI कोच के साथ जो आपकी अपनी की से चलता है। कोई अकाउंट नहीं, कोई विज्ञापन नहीं।",
    keywords="कैलोरी,पोषण,बारकोड,फिटनेस,वजन,उपवास,पानी,नींद,दवा,पीरियड,डाइट,जिम,सेहत,वर्कआउट",
    short="पोषण, वर्कआउट, मेडिकल रिपोर्ट और हेल्थ डेटा – आपके फ़ोन पर निजी रूप से।",
    extras_ios="विजेट, Apple Watch ऐप, Siri शॉर्टकट और 18 भाषाएँ।",
    extras_android="विजेट, ऐप शॉर्टकट, वैकल्पिक Google Drive बैकअप और 18 भाषाएँ।",
    desc="""Ayuvo एक निजी हेल्थ साथी है जिसमें सब कुछ एक जगह है: भोजन, वर्कआउट, दवाइयाँ और मेडिकल रिपोर्ट, आपका {HEALTH} डेटा एक हब में, और एक AI कोच जो पूरी तस्वीर देखता है – बिना अकाउंट और बिना Ayuvo सर्वर के।

पोषण
भोजन की 10 तक फ़ोटो लें, बारकोड स्कैन करें (Open Food Facts), बोलकर या लिखकर दर्ज करें। सेव करने से पहले कैलोरी, मैक्रो और 30 से ज़्यादा पोषक तत्व देखें। निजी लक्ष्य जिन्हें आप बदल सकते हैं।

वर्कआउट
सेट, रेप, वज़न और RPE के साथ वर्कआउट डायरी, 1,300 एक्सरसाइज़ की लाइब्रेरी, और पैदल चलने, दौड़ने, साइकिल चलाने व हाइकिंग की GPS रिकॉर्डिंग।

हेल्थ डेटा
आपकी अनुमति से Ayuvo आपके द्वारा दिया गया {HEALTH} डेटा – गतिविधि, दिल, नींद, वाइटल्स, शरीर की माप, पीरियड और बहुत कुछ – एक लोकल हब में दिखाता है, दिन से साल तक के चार्ट के साथ।

रिपोर्ट और दवाइयाँ
लैब रिपोर्ट, पर्चे, डॉक्टर के नोट और बिल एक जगह। टेक्स्ट आपके फ़ोन पर पढ़ा जाता है और जाँच के नतीजे रिपोर्ट-दर-रिपोर्ट ट्रैक होते हैं। दवाइयों के लिए शेड्यूल, रिमाइंडर और डोज़ लॉग।

पीरियड ट्रैकिंग
वैकल्पिक पीरियड ट्रैकर, कैलेंडर और अनुमानों के साथ (केवल अनुमान, गर्भनिरोधक नहीं)।

कैमरा माप
उँगली और चेहरे के स्कैन से नब्ज़, हार्ट रेट वेरिएबिलिटी और साँस की दर का अनुमान – सामान्य वेलनेस के लिए, मेडिकल माप नहीं।

AI कोच
अपनी की (Google Gemini, OpenAI, Anthropic और कई अन्य) या डिवाइस पर चलने वाला मॉडल इस्तेमाल करें। कोच आपका हेल्थ डेटा तभी देखता है जब आप अनुमति दें।

{EXTRAS}

प्राइवेसी
कोई अकाउंट नहीं, कोई विज्ञापन नहीं, कोई एनालिटिक्स नहीं। आपका डेटा इसी डिवाइस पर रहता है और केवल आपके कहने पर ही बाहर जाता है।

Ayuvo कोई मेडिकल डिवाइस नहीं है और मेडिकल सलाह नहीं देता।""",
)

L["ja"] = dict(
    name="Ayuvo – AIヘルスコンパニオン",
    subtitle="食事・運動・AIコーチ",
    promo="食事、運動、ファスティング、水分、そしてあなたのヘルスケアデータをひとつのプライベートなアプリに。自分のキーで使えるAIコーチ付き。アカウント不要、広告なし。",
    keywords="カロリー,栄養,バーコード,フィットネス,体重,断食,水分,睡眠,薬,生理,ダイエット,筋トレ,健康",
    short="食事、運動、検査結果、ヘルスケアデータを、あなたのスマホでプライベートに管理。",
    extras_ios="ウィジェット、Apple Watchアプリ、Siriショートカット、18言語に対応。",
    extras_android="ウィジェット、アプリのショートカット、任意のGoogle Driveバックアップ、18言語に対応。",
    desc="""Ayuvoは、すべてをひとつにまとめるプライベートな健康アプリです。食事、運動、お薬、医療記録、{HEALTH}のデータをひとつのハブに集め、全体を見渡せるAIコーチに相談できます。アカウントもAyuvoのサーバーもありません。

食事
食事の写真を最大10枚撮影、バーコードをスキャン（Open Food Facts）、または音声や文字で記録。保存前にカロリー、PFC、30種類以上の栄養素を確認できます。目標は自分で調整できます。

運動
セット、回数、重量、RPEを記録できるトレーニング日記、1,300種類のエクササイズライブラリ、ウォーキング・ランニング・サイクリング・ハイキングのGPS記録。

ヘルスケアデータ
許可した{HEALTH}のデータ（アクティビティ、心臓、睡眠、バイタル、身体測定、月経周期など）をローカルのハブに取り込み、日から年までのグラフで表示します。

医療記録とお薬
検査結果、処方箋、診療記録、請求書をひとつの場所に。文字はスマホ上で読み取られ、検査値はレポートをまたいで追跡できます。お薬はスケジュール、リマインダー、服薬記録で管理。

月経周期
カレンダーと予測付きの任意の生理トラッカー（予測のみで、避妊法ではありません）。

カメラ測定
指や顔のスキャンで心拍数、心拍変動、呼吸数を推定します。一般的なウェルネス用で、医療用の測定ではありません。

AIコーチ
自分のキー（Google Gemini、OpenAI、Anthropicなど）またはデバイス上のモデルを利用できます。ヘルスケアデータは、あなたが許可した場合のみコーチが参照します。

{EXTRAS}

プライバシー
アカウント、広告、分析ツールはありません。データはこのデバイスに保存され、あなたが操作したときだけ外部に送られます。

Ayuvoは医療機器ではなく、医療上の助言は行いません。""",
)

L["ko"] = dict(
    name="Ayuvo – AI 건강 도우미",
    subtitle="영양, 운동, AI 코치",
    promo="영양, 운동, 단식, 수분 그리고 건강 데이터를 하나의 비공개 앱에서. 내 키로 작동하는 AI 코치까지. 계정도 광고도 없습니다.",
    keywords="칼로리,영양,바코드,피트니스,체중,단식,수분,수면,약,생리,다이어트,헬스,건강,운동",
    short="영양, 운동, 검사 기록과 건강 데이터를 내 휴대폰에서 비공개로 관리하세요.",
    extras_ios="위젯, Apple Watch 앱, Siri 단축어, 18개 언어 지원.",
    extras_android="위젯, 앱 바로가기, 선택형 Google Drive 백업, 18개 언어 지원.",
    desc="""Ayuvo는 모든 것을 한곳에 모은 비공개 건강 도우미입니다. 식사, 운동, 약, 의료 기록, {HEALTH} 데이터를 하나의 허브에 모으고, 전체를 보는 AI 코치에게 물어보세요. 계정도, Ayuvo 서버도 없습니다.

영양
식사 사진을 최대 10장 찍거나, 바코드를 스캔하거나(Open Food Facts), 말이나 글로 입력하세요. 저장 전에 칼로리, 탄단지, 30가지 이상의 영양소를 확인할 수 있습니다. 목표는 직접 조정할 수 있습니다.

운동
세트, 횟수, 무게, RPE를 기록하는 운동 일지, 1,300가지 운동 라이브러리, 걷기·달리기·자전거·하이킹 GPS 기록.

건강 데이터
허용한 {HEALTH} 데이터(활동, 심장, 수면, 바이탈, 신체 측정, 생리 주기 등)를 로컬 허브로 가져와 일 단위부터 연 단위까지 차트로 보여 줍니다.

의료 기록과 약
검사 결과, 처방전, 진료 기록, 영수증을 한곳에. 텍스트는 휴대폰에서 읽히고 검사 수치는 기록 간에 추적됩니다. 약은 일정, 알림, 복용 기록으로 관리하세요.

생리 주기
캘린더와 예측이 포함된 선택형 생리 트래커(예측일 뿐이며 피임 방법이 아닙니다).

카메라 측정
손가락과 얼굴 스캔으로 심박수, 심박 변이도, 호흡수를 추정합니다. 일반적인 웰니스용이며 의료용 측정이 아닙니다.

AI 코치
내 키(Google Gemini, OpenAI, Anthropic 등) 또는 기기 내 모델을 사용하세요. 코치는 허용한 경우에만 건강 데이터를 봅니다.

{EXTRAS}

개인정보 보호
계정, 광고, 분석 도구가 없습니다. 데이터는 이 기기에 저장되며 사용자가 직접 실행할 때만 밖으로 나갑니다.

Ayuvo는 의료기기가 아니며 의학적 조언을 제공하지 않습니다.""",
)

L["zh-Hans"] = dict(
    name="Ayuvo – AI 健康助手",
    subtitle="饮食、运动、AI 教练",
    promo="饮食、运动、断食、饮水和你的健康数据，尽在一款私密应用中，还有使用你自己密钥的 AI 教练。无需账户，没有广告。",
    keywords="卡路里,营养,条形码,健身,体重,断食,饮水,睡眠,药物,经期,减肥,运动,健康,饮食",
    short="饮食、运动、检查报告和健康数据，在你的手机上私密管理。",
    extras_ios="小组件、Apple Watch 应用、Siri 快捷指令，支持 18 种语言。",
    extras_android="小组件、应用快捷方式、可选的 Google Drive 备份，支持 18 种语言。",
    desc="""Ayuvo 是一款把一切集中在一处的私密健康助手：饮食、运动、药物和病历，你的 {HEALTH} 数据汇集在一个中心，还有能看到全局的 AI 教练。无需账户，也没有 Ayuvo 服务器。

饮食
为一餐拍摄最多 10 张照片、扫描条形码（Open Food Facts），或用语音、文字记录。保存前可查看热量、三大营养素和 30 多种营养成分。个人目标可自行调整。

运动
记录组数、次数、重量和 RPE 的训练日记，1,300 个动作的动作库，以及步行、跑步、骑行和徒步的 GPS 记录。

健康数据
在你允许后，Ayuvo 会把你授权的 {HEALTH} 数据（活动、心脏、睡眠、生命体征、身体测量、经期等）同步到本地中心，并提供从日到年的图表。

病历与药物
化验单、处方、病历和账单集中在一处。文字在手机上识别，检查数值可跨报告追踪。药物支持服药计划、提醒和服药记录。

经期
可选的经期记录，附日历和预测（仅为预测，不能用于避孕）。

摄像头测量
通过手指和面部扫描估算心率、心率变异性和呼吸频率，用于一般健康参考，并非医疗测量。

AI 教练
使用你自己的密钥（Google Gemini、OpenAI、Anthropic 等）或设备端模型。只有在你允许时，教练才能查看你的健康数据。

{EXTRAS}

隐私
没有账户、广告和分析工具。你的数据保存在本设备上，只有在你主动操作时才会离开。

Ayuvo 不是医疗器械，不提供医疗建议。""",
)

L["az"] = dict(  # Google Play only (the App Store has no Azerbaijani locale)
    name="Ayuvo – Süni intellekt sağlıq",
    subtitle="",
    promo="",
    keywords="",
    short="Qidalanma, məşq, tibbi hesabatlar və sağlamlıq məlumatları – telefonunuzda.",
    extras_ios="",
    extras_android="Vidcetlər, tətbiq qısayolları, istəyə görə Google Drive ehtiyat nüsxəsi və 18 dil.",
    desc="""Ayuvo hər şeyi bir yerdə toplayan şəxsi sağlamlıq köməkçisidir: yeməklər, məşqlər, dərmanlar və tibbi sənədlər, {HEALTH} məlumatlarınız bir mərkəzdə və bütün mənzərəni görən süni intellekt məşqçisi – hesab və Ayuvo serverləri olmadan.

QİDALANMA
Yeməyin 10-a qədər şəklini çəkin, barkodu skan edin (Open Food Facts), səslə və ya yazı ilə qeyd edin. Yadda saxlamazdan əvvəl kalorini, makroları və 30-dan çox qida maddəsini yoxlayın. Dəyişdirə biləcəyiniz şəxsi hədəflər.

MƏŞQ
Dəst, təkrar, çəki və RPE ilə məşq gündəliyi, 1300 hərəkətdən ibarət kitabxana və gəzinti, qaçış, velosiped və yürüşlərin GPS qeydi.

SAĞLAMLIQ MƏLUMATLARI
İcazənizlə Ayuvo icazə verdiyiniz {HEALTH} məlumatlarını – fəallıq, ürək, yuxu, həyati göstəricilər, bədən ölçüləri, aybaşı dövrü və s. – gündən ilə qədər qrafiklərlə yerli mərkəzə köçürür.

SƏNƏDLƏR VƏ DƏRMANLAR
Analizlər, reseptlər, həkim qeydləri və hesablar bir yerdə. Mətn telefonda oxunur, nəticələr hesabatdan hesabata izlənir. Dərmanlar üçün cədvəl, xatırlatmalar və qəbul jurnalı.

AYBAŞI DÖVRÜ
Təqvim və proqnozlarla istəyə bağlı aybaşı izləyicisi (yalnız proqnoz, kontrasepsiya vasitəsi deyil).

KAMERA ÖLÇMƏLƏRİ
Barmaq və üz skanı nəbzi, ürək ritminin dəyişkənliyini və tənəffüs tezliyini təxmin edir – ümumi rifah üçün, tibbi ölçmə deyil.

SÜNİ İNTELLEKT MƏŞQÇİSİ
Öz açarınızı (Google Gemini, OpenAI, Anthropic və digərləri) və ya cihazdakı modeli istifadə edin. Məşqçi sağlamlıq məlumatlarınızı yalnız siz icazə versəniz görür.

{EXTRAS}

MƏXFİLİK
Hesab, reklam və analitika yoxdur. Məlumatlarınız bu cihazda saxlanılır və yalnız siz istədikdə onu tərk edir.

Ayuvo tibbi cihaz deyil və tibbi məsləhət vermir.""",
)

# Store locale codes.
ASC_LOCALE = {"de": "de-DE", "fr": "fr-FR", "es": "es-ES", "it": "it", "pt-BR": "pt-BR", "nl": "nl-NL", "pl": "pl",
              "cs": "cs", "ro": "ro", "ru": "ru", "uk": "uk", "ar": "ar-SA", "hi": "hi", "ja": "ja", "ko": "ko",
              "zh-Hans": "zh-Hans"}
PLAY_LOCALE = {"de": "de-DE", "fr": "fr-FR", "es": "es-ES", "it": "it-IT", "pt-BR": "pt-BR", "nl": "nl-NL", "pl": "pl-PL",
               "cs": "cs-CZ", "ro": "ro", "ru": "ru-RU", "uk": "uk", "ar": "ar", "hi": "hi-IN", "ja": "ja-JP", "ko": "ko-KR",
               "zh-Hans": "zh-CN", "az": "az-AZ"}


def description(lang: str, platform: str) -> str:
    d = L[lang]
    health = "Apple Health" if platform == "ios" else "Health Connect"
    extras = d["extras_ios"] if platform == "ios" else d["extras_android"]
    return d["desc"].replace("{HEALTH}", health).replace("{EXTRAS}", extras) + URLS


def check() -> list[str]:
    errs = []
    for lang, d in L.items():
        ios = lang in ASC_LOCALE
        limits = [("name", 30), ("short", 80)] + ([("subtitle", 30), ("promo", 170), ("keywords", 100)] if ios else [])
        for k, n in limits:
            if not d[k] or len(d[k]) > n:
                errs.append(f"{lang}.{k}: {len(d[k])}/{n}")
        for p in (["ios"] if ios else []) + ["android"]:
            if len(description(lang, p)) > 4000:
                errs.append(f"{lang}.desc[{p}] too long")
        if ios and any(len(w.strip()) == 0 for w in d["keywords"].split(",")):
            errs.append(f"{lang}.keywords empty term")
    return errs


if __name__ == "__main__":
    e = check()
    for lang, d in L.items():
        print(f"{lang:8} name {len(d['name']):2} sub {len(d['subtitle']):2} promo {len(d['promo']):3} kw {len(d['keywords']):3} short {len(d['short']):2} desc {len(description(lang, 'android')):4}")
    print("ERRORS:", e or "none")
