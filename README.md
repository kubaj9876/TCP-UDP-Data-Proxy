# TCP-UDP-Data-Proxy
Distributed TCP/UDP Data Aggregation Proxy.
Zaawansowana aplikacja typu Proxy (pośrednik), zaprojektowana do agregacji danych w rozproszonych sieciach o dowolnej topologii. System umożliwia komunikację między klientami a serwerami przy użyciu różnych protokołów (TCP oraz UDP), oferując inteligentne przekazywanie żądań, zbieranie kluczy z sieci oraz mechanizmy zapobiegające zapętleniom.

Kluczowe funkcjonalności
  * Dual-Stack Architecture: Proxy nasłuchuje jednocześnie na protokołach TCP i UDP, obsługując ruch z obu źródeł w tym samym czasie.
  * Inteligentna Agregacja Danych: System potrafi zbierać nazwy kluczy (GET_NAMES) oraz ich wartości (GET_VALUE) z całej sieci połączonych węzłów.
  * Mechanizm Loop Detection: Autorska implementacja wykrywania cykli w sieci przy użyciu unikalnych identyfikatorów UUID oraz historii żądań (requestHistory), co zapobiega nieskończonemu krążeniu pakietów.
  * Zaawansowany Protokół P2P: Rozszerzony zestaw komend (z suffixem _P) dedykowany do komunikacji między węzłami proxy, umożliwiający automatyczną identyfikację „sąsiadów” jako inne jednostki proxy lub serwery danych.
  * Wielowątkowość i Synchronizacja: Wykorzystanie ExecutorService do obsługi każdego połączenia w osobnym zadaniu oraz thread-safe kolekcji (ConcurrentHashMap, synchronizedList), co gwarantuje stabilność w środowisku wysokiej współbieżności.
  * Graceful Shutdown: Mechanizm propagacji sygnału QUIT, który bezpiecznie zamyka całą sieć połączonych węzłów.
