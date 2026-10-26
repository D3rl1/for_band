package com.forband.issue;

/** 列表页批量 COUNT 的映射结果（回帖数 / +1 数共用）。 */
public class IssueCount {

    private Long issueId;
    private Long total;

    public Long getIssueId() {
        return issueId;
    }

    public void setIssueId(Long issueId) {
        this.issueId = issueId;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }
}
