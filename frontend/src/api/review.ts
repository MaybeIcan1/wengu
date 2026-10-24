import request from './request'

export const startSession = (categoryId?: string, count = 10) =>
  request.post('/review/session/start', null, { params: { categoryId, count } })
export const getSummary = () => request.get('/review/summary')
export const rateCard = (cardId: string, rating: number) =>
  request.post(`/review/${cardId}/rating?rating=${rating}`)
export const getRandom = () => request.get('/review/random')
export const checkin = () => request.post('/checkin')
export const checkinStatus = () => request.get('/checkin/status')
export const todayRank = (top = 10) => request.get('/rank/today', { params: { top } })
