# companion-web — 동승자 폰 PWA

SPEC 의 폰 화면 P1(페어링)·P2(받은 사진)·P4(저장·공유). P3(폰 편집)·P5(실내 카메라 모드)는 T15·T11.

> 변경(11/9, T10): SPEC 은 Next.js + Supabase 지만, 1차는 **로컬 서버(server/)가 `/app/` 으로 서빙하는 의존성 없는 정적 PWA**로 한다.
> 서버 API 는 Supabase Edge Function 이름을 그대로 따라서(`pairing-create`, `pairing-claim`, `deliver`=`/sync/results`, `share-link`=`/sync/share`) 나중에 옮기기 쉽다.

- 접속: 차량 화면 QR → `http://<PC LAN IP>:8787/app/?pair=<토큰>` (폰과 PC 가 같은 Wi-Fi)
- 실시간: SSE `/sync/events?deviceId=` (Supabase Realtime 채널 `device:{id}` 대체)
- 제한: http(LAN)에서는 서비스 워커·Web Share API·클립보드가 막힌다 → '기기에 저장'(다운로드)과 공유 링크로 대신. HTTPS 로 띄우면(예: Supabase 이전, 터널) 그대로 동작.
- Windows 방화벽이 8787 인바운드를 막으면 폰에서 접속이 안 된다 → 개인 네트워크 허용 필요.
