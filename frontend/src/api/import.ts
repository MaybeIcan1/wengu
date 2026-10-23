import request from './request'

/** 上传 docx（multipart）。标题语义由后端自动检测，预览页可纠正 */
export const uploadDocx = (file: File, force = false) => {
  const fd = new FormData()
  fd.append('file', file)
  if (force) fd.append('force', 'true')   // 忽略去重，强制重新解析
  return request.post('/import/docx', fd)
}

export const getTask = (id: string) => request.get(`/import/task/${id}`)
export const getDrafts = (id: string) => request.get(`/import/task/${id}/drafts`)
export const updateDraft = (id: string, d: any) => request.put(`/import/draft/${id}`, d)
export const confirmImport = (id: string) => request.post(`/import/task/${id}/confirm`)
export const discardImport = (id: string) => request.delete(`/import/task/${id}`)

/** 预览页纠正标题语义（CATEGORY / QUESTION），后端重新拆分并返回草稿数 */
export const reparse = (id: string, mode: 'CATEGORY' | 'QUESTION') =>
  request.post(`/import/task/${id}/reparse?mode=${mode}`)
