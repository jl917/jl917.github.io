# RAIL

RAIL(Response Animation Idle Load)의 성능의 핵심은 '사용자에게 초점을 맞추세요. 최종 목표는 사이트가 특정 장치에서 빠르게 작동하도록 만드는 것이 아니라 사용자를 행복하게 만드는 것'입니다.

## Response

100ms 이내에 사용자 입력을 승인하여 즉시 사용자에게 응답합니다.

## Animation

애니메이션을 적용할 때, 각 프레임을 16ms 미만으로 렌더링하여 일관성을 유지하고 버벅거림을 방지하세요.

## Idle

기본 JavaScript 스레드를 사용하는 경우, 50ms 미만의 시간 동안 청크로 작업하여 사용자 상호작용을 위한 스레드를 확보합니다.

## Load

5초 이내에 상호작용 가능한 콘텐츠를 제공합니다.

### 참고

- https://web.dev/articles/rail?hl=ko#focus_on_the_user
