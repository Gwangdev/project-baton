/**
 * 마지막으로 보낸 요청의 응답만 화면에 반영하게 한다.
 *
 * 필터를 빠르게 바꾸면 요청이 여러 개 동시에 나가고, 응답은 보낸 순서대로 오지 않는다. 늦게 도착한 이전 요청의
 * 응답이 최신 결과를 덮으면 화면의 필터와 목록이 어긋난다. 그래서 요청마다 순번을 받고, 응답이 왔을 때 그 순번이
 * 여전히 마지막인지 확인한다.
 */
export function latestRequest(): () => () => boolean {
  let current = 0
  return () => {
    const id = ++current
    return () => id === current
  }
}
