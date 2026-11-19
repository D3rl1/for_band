package com.forband.member;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MemberMapper extends BaseMapper<Member> {
    @Select("SELECT * FROM member WHERE id = #{id} FOR UPDATE")
    Member selectForUpdate(@Param("id") long id);
}
